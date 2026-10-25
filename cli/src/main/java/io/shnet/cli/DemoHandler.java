package io.shnet.cli;

import io.shnet.Shnet;

public final class DemoHandler implements Shnet.Handler {
    @Override
    public Shnet.Response handle(Shnet.Request request) {
        if ("/".equals(request.path)) {
            return Shnet.Response.text(200, "text/plain; charset=utf-8", "hello from shnet");
        }
        return Shnet.Response.text(404, "text/plain; charset=utf-8", "Not Found");
    }
}
