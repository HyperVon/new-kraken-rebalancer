import '@testing-library/jest-dom';

class MockEventSource extends EventTarget {
    url: string;
    onmessage: ((this: MockEventSource, ev: MessageEvent) => any) | null = null;
    onerror: ((this: MockEventSource, ev: Event) => any) | null = null;

    constructor(url: string) {
        super();
        this.url = url;
    }

    close() {}
}

global.EventSource = MockEventSource as any;
