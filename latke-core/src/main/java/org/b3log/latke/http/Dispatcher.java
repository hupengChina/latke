/*
 * Copyright (c) 2009-present, b3log.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.latke.http;

import org.b3log.latke.http.function.ContextHandler;
import org.b3log.latke.http.handler.*;
import org.b3log.latke.http.renderer.AbstractResponseRenderer;
import org.b3log.latke.http.renderer.Http404Renderer;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dispatch-controller for HTTP request dispatching.
 *
 * @author <a href="https://hacpai.com/member/mainlove">Love Yao</a>
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.0.2.2, Dec 2, 2018
 */
public final class Dispatcher {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Dispatcher.class);

    /**
     * Handlers.
     */
    public static final List<Handler> HANDLERS = new ArrayList<>();

    /**
     * Start request handler, handle before all handlers.
     */
    public static Handler startRequestHandler;

    /**
     * End request handler, handle after all handlers.
     */
    public static Handler endRequestHandler;

    static {
        HANDLERS.add(new StaticResourceHandler());
        HANDLERS.add(new RouteHandler());
        HANDLERS.add(new BeforeHandleHandler());
        HANDLERS.add(new ContextHandleHandler());
        HANDLERS.add(new AfterHandleHandler());
    }

    /**
     * Handle flow.
     *
     * @param request  the specified request
     * @param response the specified response
     * @return context
     */
    public static RequestContext handle(final Request request, final Response response) {
        final RequestContext ret = new RequestContext();
        ret.setRequest(request);
        request.context = ret;
        ret.setResponse(response);
        response.context = ret;

        if (null != startRequestHandler) {
            try {
                startRequestHandler.handle(ret);
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Start request handle failed", e);
            }
        }

        for (final Handler handler : HANDLERS) {
            ret.addHandler(handler);
        }
        ret.handle();
        renderResponse(ret);

        if (null != endRequestHandler) {
            try {
                endRequestHandler.handle(ret);
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "End request handle failed", e);
            }
        }

        return ret;
    }

    /**
     * Renders HTTP response.
     *
     * @param context {@link RequestContext}
     */
    public static void renderResponse(final RequestContext context) {
        final Response response = context.getResponse();
        if (response.isCommitted()) { // Response sends redirect or error
            return;
        }

        AbstractResponseRenderer renderer = context.getRenderer();
        if (null == renderer) {
            renderer = new Http404Renderer();
        }
        renderer.render(context);
    }

    /**
     * Programmatic routers.
     */
    private static List<Router> routers = new ArrayList<>();

    /**
     * HTTP DELETE routing.
     *
     * @param uriTemplate the specified request URI template
     * @param handler     the specified handler
     * @return router
     */
    public static Router delete(final String uriTemplate, final ContextHandler handler) {
        return route().delete(uriTemplate, handler);
    }

    /**
     * HTTP PUT routing.
     *
     * @param uriTemplate the specified request URI template
     * @param handler     the specified handler
     * @return router
     */
    public static Router put(final String uriTemplate, final ContextHandler handler) {
        return route().put(uriTemplate, handler);
    }

    /**
     * HTTP GET routing.
     *
     * @param uriTemplate the specified request URI template
     * @param handler     the specified handler
     * @return router
     */
    public static Router get(final String uriTemplate, final ContextHandler handler) {
        return route().get(uriTemplate, handler);
    }

    /**
     * HTTP POST routing.
     *
     * @param uriTemplate the specified request URI template
     * @param handler     the specified handler
     * @return router
     */
    public static Router post(final String uriTemplate, final ContextHandler handler) {
        return route().post(uriTemplate, handler);
    }

    /**
     * Registers a new router.
     *
     * @return router
     */
    public static Router route() {
        final Router ret = new Router();
        routers.add(ret);

        return ret;
    }

    /**
     * Error handle router.
     */
    static Router errorHandleRouter;

    /**
     * Error status routing.
     *
     * @param uriTemplate the specified request URI template
     * @param handler     the specified handler
     */
    public static void error(final String uriTemplate, final ContextHandler handler) {
        errorHandleRouter = new Router().get(uriTemplate, handler);
    }

    /**
     * WebSocket channels.
     */
    static Map<String, WebSocketChannel> webSocketChannels = new ConcurrentHashMap<>();

    /**
     * WebSocket channel routing.
     *
     * @param uri              the specified URI
     * @param webSocketChannel the specified WebSocket channel
     */
    public static void webSocket(final String uri, final WebSocketChannel webSocketChannel) {
        webSocketChannels.put(uri, webSocketChannel);
    }

    /**
     * Performs mapping for all routers.
     */
    public static void mapping() {
        for (final Router router : routers) {
            final ContextHandlerMeta contextHandlerMeta = router.toContextHandlerMeta();
            RouteHandler.addContextHandlerMeta(contextHandlerMeta);
        }
    }

    /**
     * Programmatic router.
     *
     * @author <a href="http://88250.b3log.org">Liang Ding</a>
     * @version 1.0.0.0, Dec 2, 2018
     */
    public static class Router {
        private List<String> uriTemplates = new ArrayList<>();
        private List<HttpMethod> httpRequestMethods = new ArrayList<>();
        private ContextHandler handler;
        private Method method;

        public Router delete(final String uriTemplate, final ContextHandler handler) {
            return delete(new String[]{uriTemplate}, handler);
        }

        public Router delete(final String[] uriTemplates, final ContextHandler handler) {
            return delete().uris(uriTemplates).handler(handler);
        }

        public Router put(final String uriTemplate, final ContextHandler handler) {
            return put(new String[]{uriTemplate}, handler);
        }

        public Router put(final String[] uriTemplates, final ContextHandler handler) {
            return put().uris(uriTemplates).handler(handler);
        }

        public Router post(final String uriTemplate, final ContextHandler handler) {
            return post(new String[]{uriTemplate}, handler);
        }

        public Router post(final String[] uriTemplates, final ContextHandler handler) {
            return post().uris(uriTemplates).handler(handler);
        }

        public Router get(final String uriTemplate, final ContextHandler handler) {
            return get(new String[]{uriTemplate}, handler);
        }

        public Router get(final String[] uriTemplates, final ContextHandler handler) {
            return get().uris(uriTemplates).handler(handler);
        }

        public Router uris(final String[] uriTemplates) {
            for (int i = 0; i < uriTemplates.length; i++) {
                uri(uriTemplates[i]);
            }

            return this;
        }

        public Router uri(final String uriTemplate) {
            if (!uriTemplates.contains(uriTemplate)) {
                uriTemplates.add(uriTemplate);
            }

            return this;
        }

        public Router get() {
            if (!httpRequestMethods.contains(HttpMethod.GET)) {
                httpRequestMethods.add(HttpMethod.GET);
            }

            return this;
        }

        public Router post() {
            if (!httpRequestMethods.contains(HttpMethod.POST)) {
                httpRequestMethods.add(HttpMethod.POST);
            }

            return this;
        }

        public Router delete() {
            if (!httpRequestMethods.contains(HttpMethod.DELETE)) {
                httpRequestMethods.add(HttpMethod.DELETE);
            }

            return this;
        }

        public Router put() {
            if (!httpRequestMethods.contains(HttpMethod.PUT)) {
                httpRequestMethods.add(HttpMethod.PUT);
            }

            return this;
        }

        public Router head() {
            if (!httpRequestMethods.contains(HttpMethod.HEAD)) {
                httpRequestMethods.add(HttpMethod.HEAD);
            }

            return this;
        }

        public Router options() {
            if (!httpRequestMethods.contains(HttpMethod.OPTIONS)) {
                httpRequestMethods.add(HttpMethod.OPTIONS);
            }

            return this;
        }

        public Router trace() {
            if (!httpRequestMethods.contains(HttpMethod.TRACE)) {
                httpRequestMethods.add(HttpMethod.TRACE);
            }

            return this;
        }

        public Router handler(final ContextHandler handler) {
            this.handler = handler;
            final Class clazz = handler.getClass();
            try {
                final Serializable lambda = handler;
                // Latke 框架中 "writeReplace" 是个什么魔数？ https://hacpai.com/article/1568102022352
                final Method m = clazz.getDeclaredMethod("writeReplace");
                m.setAccessible(true);
                final SerializedLambda sl = (SerializedLambda) m.invoke(lambda);
                final String implClassName = sl.getImplClass().replaceAll("/", ".");
                final Class<?> implClass = Class.forName(implClassName);
                this.method = implClass.getDeclaredMethod(sl.getImplMethodName(), RequestContext.class);
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Found lambda method reference impl method failed", e);
            }

            return this;
        }

        ContextHandlerMeta toContextHandlerMeta() {
            final ContextHandlerMeta ret = new ContextHandlerMeta();
            ret.setUriTemplates(uriTemplates.toArray(new String[0]));
            ret.setHttpMethods(httpRequestMethods.toArray(new HttpMethod[0]));
            ret.setInvokeHolder(method);
            ret.setHandler(handler);
            ret.initProcessAdvices();

            return ret;
        }
    }
}
