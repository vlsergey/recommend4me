import { Component, type ReactNode } from "react";

/**
 * A part of the interface that failed to draw shows what went wrong in its place instead of
 * taking the whole application down with it: React unmounts everything above an error nobody
 * catches. [resetKey] — when it changes (another work opened), the part is drawn anew.
 */
export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string; title?: string }, { error: Error | null; key?: string }> {
  state: { error: Error | null; key?: string } = { error: null, key: this.props.resetKey };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  static getDerivedStateFromProps(props: { resetKey?: string }, state: { error: Error | null; key?: string }) {
    return props.resetKey !== state.key ? { error: null, key: props.resetKey } : null;
  }

  componentDidCatch(error: Error) {
    console.error("recommend4me: a part of the interface failed to draw", error);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="m-4 rounded-lg border border-destructive/50 bg-destructive/5 p-4 text-sm">
        <div className="font-semibold text-destructive">{this.props.title ?? "Не удалось показать"}</div>
        <div className="mt-1 font-mono text-xs break-all text-muted-foreground">{this.state.error.message}</div>
      </div>
    );
  }
}
