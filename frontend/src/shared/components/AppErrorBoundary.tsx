import { Component, createRef, type ReactNode } from 'react';

import { translate } from '../i18n/messages';

interface AppErrorBoundaryProps {
  children: ReactNode;
}

interface AppErrorBoundaryState {
  failed: boolean;
}

export class AppErrorBoundary extends Component<
  AppErrorBoundaryProps,
  AppErrorBoundaryState
> {
  state: AppErrorBoundaryState = { failed: false };
  private readonly fatalErrorRef = createRef<HTMLElement>();

  static getDerivedStateFromError(): AppErrorBoundaryState {
    return { failed: true };
  }

  componentDidMount() {
    this.focusFallback();
  }

  componentDidCatch(error: Error) {
    if (typeof globalThis.reportError === 'function') {
      globalThis.reportError(error);
    } else {
      console.error(error);
    }
  }

  componentDidUpdate(
    _previousProps: AppErrorBoundaryProps,
    previousState: AppErrorBoundaryState,
  ) {
    if (!previousState.failed && this.state.failed) {
      this.focusFallback();
    }
  }

  private focusFallback() {
    if (this.state.failed) {
      this.fatalErrorRef.current?.focus();
    }
  }

  private reload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.failed) {
      return (
        <main
          ref={this.fatalErrorRef}
          className="fatal-error"
          role="alert"
          aria-labelledby="fatal-error-title"
          tabIndex={-1}
        >
          <div className="fatal-error__content">
            <h1 id="fatal-error-title">{translate('error.boundaryTitle')}</h1>
            <p>{translate('error.boundaryDescription')}</p>
            <button
              className="fatal-error__reload"
              type="button"
              onClick={this.reload}
            >
              {translate('error.reload')}
            </button>
          </div>
        </main>
      );
    }

    return this.props.children;
  }
}
