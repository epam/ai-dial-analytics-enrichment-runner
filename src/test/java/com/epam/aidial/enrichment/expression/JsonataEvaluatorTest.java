package com.epam.aidial.enrichment.expression;

import com.dashjoin.jsonata.JException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runtime bounds: heavy but ordinary mappings pass under the production bounds, runaway ones fail
 * instead of holding a worker.
 */
class JsonataEvaluatorTest {

    private final JsonataEvaluator evaluator = new JsonataEvaluator();

    @Test
    void ordinaryExpressionEvaluates() {
        assertThat(evaluator.evaluate("a & '-' & b", Map.of("a", "x", "b", "y"))).isEqualTo("x-y");
    }

    /**
     * The shape the library's own depth counter gets wrong: a lambda building a multi-key object leaks one
     * count per element there, so this mapping would be refused for having a long array rather than deep
     * nesting. The time bound is relaxed so a slow build machine cannot fail it for the wrong reason; the depth
     * bound under test stays the production one.
     */
    @Test
    void mappingLargeArrayIsNotMistakenForDeepNesting() {
        JsonataEvaluator relaxed = new JsonataEvaluator(Duration.ofSeconds(30), JsonataEvaluator.MAX_DEPTH);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            messages.add(Map.of("role", "user", "content", "text " + i, "tags", List.of("a", "b")));
        }

        Object result = relaxed.evaluate(
                "messages ~> $map(function($m, $i) { {'i': $i, 'len': $length($m.content), 'tags': $join($m.tags, ',')} })",
                Map.of("messages", messages));

        assertThat(result).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(5000);
    }

    @Test
    void recursiveWalkOfDeepTreeFitsUnderTheDepthBound() {
        Map<String, Object> tree = Map.of("v", 0);
        for (int i = 1; i <= 50; i++) {
            tree = Map.of("v", 1, "children", List.of(tree));
        }

        Object result = evaluator.evaluate(
                "($walk := function($n) { $n.children ? $n.v + $sum($n.children.$walk($)) : $n.v }; $walk(tree))",
                Map.of("tree", tree));

        assertThat(result).isEqualTo(50);
    }

    @Test
    void runawayRecursionFailsOnTheDepthBound() {
        assertThatThrownBy(() -> evaluator.evaluate("($f := function($n) { 1 + $f($n + 1) }; $f(0))", Map.of()))
                .isInstanceOf(JException.class)
                .hasMessageContaining("depth " + JsonataEvaluator.MAX_DEPTH);
    }

    /**
     * The library rewrites any exception other than {@link JException} raised inside a boolean expression into a
     * bare "Unexpected", so the bound's reason survives only because it is thrown as one.
     */
    @Test
    void runawayRecursionInsideBooleanExpressionKeepsTheDepthReason() {
        assertThatThrownBy(() -> evaluator.evaluate("($f := function($n) { 1 + $f($n + 1) }; $f(0) > 0)", Map.of()))
                .isInstanceOf(JException.class)
                .hasMessageContaining("nesting exceeded depth " + JsonataEvaluator.MAX_DEPTH);
    }

    /** Tail recursion does not deepen the stack, so the time bound is what stops it. */
    @Test
    void runawayTailRecursionFailsOnTheTimeBound() {
        JsonataEvaluator fast = new JsonataEvaluator(Duration.ofMillis(50), JsonataEvaluator.MAX_DEPTH);

        assertThatThrownBy(() -> fast.evaluate("($f := function($n) { $f($n + 1) }; $f(0))", Map.of()))
                .isInstanceOf(JException.class)
                .hasMessageContaining("exceeded 50 ms");
    }

    /** The clock starts per evaluation, so a long-lived compiled expression does not time out on later calls. */
    @Test
    void theTimeBoundRestartsForEachEvaluation() throws Exception {
        JsonataEvaluator fast = new JsonataEvaluator(Duration.ofMillis(200), JsonataEvaluator.MAX_DEPTH);

        assertThat(fast.evaluate("a", Map.of("a", 1))).isEqualTo(1);
        Thread.sleep(300);
        assertThat(fast.evaluate("a", Map.of("a", 2))).isEqualTo(2);
    }

    /**
     * An overflow the bounds did not catch becomes a runtime exception, which the callers dead-letter for the
     * one input, rather than an {@link Error} that fails the whole chunk.
     */
    @Test
    void stackOverflowBecomesRuntimeException() throws Exception {
        JsonataEvaluator unbounded = new JsonataEvaluator(Duration.ofMinutes(1), Integer.MAX_VALUE);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread small = new Thread(null, () -> {
            try {
                unbounded.evaluate("($f := function($n) { 1 + $f($n + 1) }; $f(0))", Map.of());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "small-stack", 256 * 1024);
        small.start();
        small.join();

        assertThat(thrown.get()).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(StackOverflowError.class);
    }
}
