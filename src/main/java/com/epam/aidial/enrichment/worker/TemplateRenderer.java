package com.epam.aidial.enrichment.worker;

import com.epam.aidial.enrichment.exception.UnresolvedPlaceholderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a transform's request template by substituting {@code {{input-var}}} placeholders with the unit of
 * work's extracted input values. String values are JSON-string-escaped for safe embedding inside a JSON
 * string literal (the dominant chat-prompt case); numbers/booleans are inserted as JSON literals; a null
 * value is rendered as empty (an absent optional passed through).
 *
 * <p>A <em>collection</em> value — a list or a map — is rendered as JSON and then escaped like a string,
 * which is what lets a group pipeline interpolate its assembled member list inside the template's content
 * string. Escaping is not optional there: splicing raw JSON into a JSON string literal would leave
 * unescaped quotes and corrupt the request body.
 *
 * <p><strong>Every placeholder the template carries must be bound.</strong> One that is not fails the unit of
 * work here, before a request is even assembled, rather than reaching the model as literal
 * <code>{{name}}</code> text. Neither of the two tempting alternatives is acceptable: rendering the
 * placeholder through asks the model a question with a hole in it, and substituting empty string for an
 * unbound variable asks a subtly different question and hides that it did. Both turn a detectable fault into
 * a charged call and a stored label — the most expensive shape of failure the runner has, because nothing
 * about it looks wrong afterwards.
 *
 * <p>The check reads the <em>template</em> and compares the names it asks for against the keys actually
 * bound, rather than scanning the rendered output for surviving braces. A scan of the output would fire on a
 * row whose own data happens to contain <code>{{…}}</code> — source content the runner must pass through
 * untouched — and would dead-letter a perfectly good row for its payload's punctuation.
 *
 * <p><strong>Substitution is single-pass over the template</strong>, so that "passed through untouched" is
 * true of every value and not only of names nothing bound. Substituting each variable in turn over the
 * accumulated result — the obvious loop, and what this did — re-scans text that is already <em>data</em>:
 * a row whose value contains <code>{{other}}</code> had it replaced as soon as {@code other} came later in
 * the input map, so the same row rendered differently depending on iteration order and on which other
 * variables the declaration happened to bind. One pass with {@link Matcher#appendReplacement} visits each
 * position in the template exactly once and never looks at what it wrote.
 */
@Component
@RequiredArgsConstructor
public class TemplateRenderer {

    /**
     * One placeholder, matched non-greedily and without crossing a brace. The captured token is stripped of
     * surrounding whitespace before its suffix and name are read, so {@code {{ members }}} and
     * {@code {{ members:json }}} name {@code members}, as ADAS's placeholder rule reads them. The grammar matches
     * ADAS's: a template ADAS admits binds the same names here, rather than passing admission and then failing
     * every unit as unbound. Binding and substitution read the token through the same {@link #nameOf}, so there
     * is no second grammar that could accept what substitution will not.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)}}");

    /**
     * The suffix that renders a value as a JSON literal for value position, {@code {{name:json}}}. Only this exact
     * suffix is stripped; any other stays part of the name, which then binds to nothing and fails the unit as
     * unbound rather than rendering through.
     */
    static final String JSON_SUFFIX = ":json";

    private final JsonMapper jsonMapper;

    public String render(String template, Map<String, Object> inputs) {
        if (template == null) {
            // Unreachable from the feed since the admission gate refuses an llm transform that declares no
            // request_template, and kept anyway because the alternative is worse than a refusal: the old
            // code returned null here (or threw an NPE, depending on whether anything was bound) and the
            // request builder then failed to parse it, so a missing declaration member arrived as a Jackson
            // error dead-lettered at `output_map`. Named here instead.
            throw new IllegalArgumentException("transform declares no request_template, so nothing can be rendered");
        }
        requireEveryPlaceholderBound(template, inputs);

        // One pass over the TEMPLATE, never over what has already been written: a substituted value is data
        // and must not be re-scanned for placeholders. See the class javadoc.
        StringBuilder rendered = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            // containsKey holds for every match the assertion above let through; the branch is what keeps
            // that true rather than assumed, and leaves an unbound name as written rather than as "null".
            String token = matcher.group(1).strip();
            boolean raw = isRaw(token);
            String name = nameOf(token);
            String replacement = !inputs.containsKey(name) ? matcher.group()
                    : raw ? rawReplacement(inputs.get(name)) : replacement(inputs.get(name));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * Fails the unit of work when the template names a variable the inputs do not carry.
     *
     * <p>Reported in one exception listing every offending name rather than one per name: a declaration that
     * renamed a variable typically breaks several placeholders at once, and an operator fixing it wants the
     * whole list from the first dead-letter item rather than one name per re-run.
     *
     * <p>A bound variable whose <em>value</em> is null is not a fault here. That is an absent optional, which
     * the substitution renders as empty deliberately and which the declaration asked for by binding the
     * variable at all; the fault this guards is a name nothing bound.
     */
    private static void requireEveryPlaceholderBound(String template, Map<String, Object> inputs) {
        Set<String> unresolved = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String name = nameOf(matcher.group(1).strip());
            if (!inputs.containsKey(name)) {
                unresolved.add(name);
            }
        }
        if (!unresolved.isEmpty()) {
            throw new UnresolvedPlaceholderException(new ArrayList<>(unresolved));
        }
    }

    private String replacement(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            // Strip the surrounding quotes: the template supplies them, we supply the escaped inner text.
            return innerText(jsonMapper.writeValueAsString(value));
        }
        if (value instanceof Iterable<?> || value instanceof Map<?, ?>) {
            // A collection is prompt *text* — a JSON rendering of the members that belongs inside the
            // template's content string — so it is escaped like a string rather than spliced in raw. Raw
            // insertion would put unescaped quotes inside a JSON string literal and corrupt the request.
            return innerText(jsonMapper.writeValueAsString(jsonMapper.writeValueAsString(value)));
        }
        // Numbers and booleans stay raw JSON literals: those interpolate in value position.
        return jsonMapper.writeValueAsString(value);
    }

    private static boolean isRaw(String token) {
        return token.endsWith(JSON_SUFFIX) && token.length() > JSON_SUFFIX.length();
    }

    /** The variable a placeholder token names: the token itself, or the part before {@link #JSON_SUFFIX}. */
    private static String nameOf(String token) {
        return isRaw(token) ? token.substring(0, token.length() - JSON_SUFFIX.length()) : token;
    }

    /**
     * A value as a JSON literal for value position: a string quoted, a number or boolean bare, a list or map as its
     * JSON text, and a bound null as {@code null}. Unlike {@link #replacement} nothing is escaped a second time, so
     * the template places it outside a string literal.
     */
    private String rawReplacement(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    private static String innerText(String jsonString) {
        return jsonString.substring(1, jsonString.length() - 1);
    }
}
