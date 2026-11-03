import SwiftUI
import WebKit

/// The Microsoft sign-in page. The OAuth redirect ends on login.live.com/oauth20_desktop.srf?code=...; the code is read from it.
struct MicrosoftLoginView: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> WKWebView {
        let web = WKWebView(frame: .zero)
        web.navigationDelegate = context.coordinator
        web.load(URLRequest(url: MicrosoftAuth.authorizeURL))
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        let onCode: (String) -> Void
        private var done = false

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if !done, let url = navigationAction.request.url, url.absoluteString.hasPrefix(MicrosoftAuth.redirectURI) {
                let code = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "code" })?.value
                if let code {
                    done = true
                    decisionHandler(.cancel)
                    onCode(code)
                    return
                }
            }
            decisionHandler(.allow)
        }
    }
}
