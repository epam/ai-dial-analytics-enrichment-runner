package com.epam.aidial.enrichment.expression;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates a pipeline's JSONata expressions, caching each compiled expression and serialising evaluation
 * on it.
 *
 * <p>Both halves are requirements rather than optimisations, which is why they live in one place instead of
 * being re-implemented per call site. <em>Compilation</em> is the expensive step and the expressions are a
 * small fixed set — one per declared {@code vars} entry and per {@code outputs} transform — so they are
 * compiled once and reused. <em>Evaluation</em> is not thread-safe: a {@link Jsonata} instance carries
 * per-evaluation state, and the runner evaluates the same pipeline's expressions from several db-scheduler
 * worker threads at once, so every evaluation holds the instance's monitor.
 *
 * <p>The cache is keyed by the expression text and bounded by the distinct expressions the runner has
 * actually evaluated, so a re-declared pipeline adds an entry rather than mutating one.
 *
 * <p>Every evaluation runs under a bound on elapsed time and on nesting depth. The input is row data or a
 * model reply, and evaluation holds the expression's monitor, so one pathological input would otherwise stall
 * every worker thread evaluating the same expression. Both bounds are checked each time the evaluator enters
 * an expression node, so they stop a runaway loop or recursion but not a single slow built-in call.
 *
 * <ul>
 *   <li>{@link #MAX_DEPTH} counts nested node evaluations, roughly three per level of a recursive user
 *       function. Ordinary mappings stay in single digits however large their input array, and a recursive
 *       walk of a 50-level tree reaches about 155. The bound sits well below the depth at which a 512 KiB
 *       thread stack overflows, about 430, so the bound fails first.</li>
 *   <li>{@link #TIMEOUT} is far above the tens of milliseconds a heavy mapping over thousands of array
 *       elements takes on a single cold CPU, and it caps how long a runaway expression holds a worker.</li>
 * </ul>
 *
 * <p>The bounds are tracked here rather than with the library's {@code Frame.setRuntimeBounds}, whose depth
 * counter skips the exit of some nested evaluations: a {@code $map} whose lambda builds a two-key object
 * leaks one count per element, so its depth grows with the input array and an ordinary mapping over a few
 * hundred elements would be refused.
 *
 * <p>Evaluation failures propagate as the {@link RuntimeException} the library threw, a bound exceeded as a
 * {@link JException}, and a {@link StackOverflowError} — a deep built-in call the bounds cannot see — as a
 * {@link RuntimeException}, so that one input is dead-lettered rather than failing its whole chunk. Each
 * caller wraps them in its own stage-specific exception — {@code input_map} or {@code output_map} — because
 * the stage is what decides how the unit of work is dead-lettered, and this class does not know it.
 */
@Component
public class JsonataEvaluator {

    static final Duration TIMEOUT = Duration.ofSeconds(2);
    static final int MAX_DEPTH = 300;

    private final Map<String, Jsonata> compiled = new ConcurrentHashMap<>();
    private final long timeoutNanos;
    private final int maxDepth;

    public JsonataEvaluator() {
        this(TIMEOUT, MAX_DEPTH);
    }

    /** Tighter bounds than production, for tests that exercise them. */
    JsonataEvaluator(Duration timeout, int maxDepth) {
        this.timeoutNanos = timeout.toNanos();
        this.maxDepth = maxDepth;
    }

    /** Evaluates {@code expression} over {@code input}, compiling it on first use. */
    public Object evaluate(String expression, Object input) {
        Jsonata jsonata = compiled.computeIfAbsent(expression, Jsonata::jsonata);
        synchronized (jsonata) {
            try {
                return jsonata.evaluate(input, bounded(jsonata));
            } catch (StackOverflowError e) {
                throw new IllegalStateException("Expression evaluation exhausted the thread stack", e);
            }
        }
    }

    /**
     * A fresh frame per evaluation, so the deadline starts with it. Counts every entry and exit, which is
     * what keeps the depth balanced. Throws {@link JException} because the library rewrites any other
     * exception raised inside a boolean expression into a bare "Unexpected", losing the reason.
     */
    private Jsonata.Frame bounded(Jsonata jsonata) {
        long deadline = System.nanoTime() + timeoutNanos;
        int[] depth = {0};
        Jsonata.Frame frame = jsonata.createFrame();
        frame.setEvaluateEntryCallback((expr, in, env) -> {
            if (++depth[0] > maxDepth) {
                throw new JException("Expression nesting exceeded depth " + maxDepth
                        + ": check for non-terminating recursion", -1);
            }
            if (System.nanoTime() - deadline > 0) {
                throw new JException("Expression evaluation exceeded " + timeoutNanos / 1_000_000 + " ms", -1);
            }
        });
        frame.setEvaluateExitCallback((expr, in, env, result) -> depth[0]--);
        return frame;
    }
}
