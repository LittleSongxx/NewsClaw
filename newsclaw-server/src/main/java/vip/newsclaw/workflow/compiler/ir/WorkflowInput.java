package vip.newsclaw.workflow.compiler.ir;

/** Declared workflow input. Type values are advisory: {@code text|json|number|boolean}. */
public record WorkflowInput(String name, String type) {
}
