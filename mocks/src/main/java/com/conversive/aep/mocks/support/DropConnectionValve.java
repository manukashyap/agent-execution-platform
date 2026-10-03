package com.conversive.aep.mocks.support;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Closes the socket without a response when a handler flagged the request for dropping.
 * Runs after the servlet returns but before Tomcat commits the (reset, empty) response.
 */
@Component
public class DropConnectionValve extends ValveBase implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    static final String DROP_ATTRIBUTE = "aep.mocks.drop";

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        getNext().invoke(request, response);
        if (request.getAttribute(DROP_ATTRIBUTE) != null) {
            response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, null);
        }
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextValves(this);
    }
}
