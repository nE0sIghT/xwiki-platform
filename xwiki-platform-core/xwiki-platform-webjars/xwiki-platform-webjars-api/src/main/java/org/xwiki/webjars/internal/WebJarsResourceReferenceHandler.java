/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.webjars.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.container.Container;
import org.xwiki.container.Request;
import org.xwiki.container.Response;
import org.xwiki.container.servlet.ServletRequest;
import org.xwiki.container.servlet.ServletResponse;
import org.xwiki.resource.AbstractResourceReferenceHandler;
import org.xwiki.resource.ResourceReference;
import org.xwiki.resource.ResourceReferenceHandlerChain;
import org.xwiki.resource.ResourceReferenceHandlerException;
import org.xwiki.resource.ResourceType;
import org.xwiki.velocity.VelocityManager;

/**
 * Handles {@code webjars} Resource References.
 *
 * @version $Id$
 * @since 6.1M2
 * @see WebJarsResourceReferenceResolver for the URL format handled
 */
@Component
@Named("webjars")
@Singleton
public class WebJarsResourceReferenceHandler extends AbstractResourceReferenceHandler<ResourceType>
{
    /**
     * Prefix for locating resource files (JavaScript, CSS) in the classloader.
     */
    private static final String WEBJARS_RESOURCE_PREFIX = "META-INF/resources/webjars/";

    /**
     * The encoding used when evaluating WebJar (text) resources.
     */
    private static final String UTF8 = "UTF-8";

    /**
     * One year duration can be considered as permanent caching.
     */
    private static final long CACHE_DURATION = 365 * 24 * 3600 * 1000L;

    @Inject
    private Logger logger;

    @Inject
    private Container container;

    /**
     * Used to evaluate the Velocity code from the WebJar resources.
     */
    @Inject
    private VelocityManager velocityManager;

    @Inject
    private ContextInitializer contextInitializer;

    /**
     * Used to determine the Content Type of the requested resource files.
     */
    private Tika tika = new Tika();

    @Override
    public List<ResourceType> getSupportedResourceReferences()
    {
        return Arrays.asList(WebJarsResourceReference.TYPE);
    }

    @Override
    public void handle(ResourceReference resourceReference, ResourceReferenceHandlerChain chain)
        throws ResourceReferenceHandlerException
    {
        // This code only handles WebJars Resource References.
        WebJarsResourceReference webJarsResourceReference = (WebJarsResourceReference) resourceReference;

        // Set up an XWiki Context for 2 reasons:
        // 1) so that we can serve a resource located in a webjar JAR installed in a Wiki Component Manager (CM). If no
        //    Context is set up then the Context CM will fallback on the Root CM and thus the webjar JAR won't be found.
        // 2) so that we can set the current user in the Context and at the same time verify that the current user has
        //    View Rights on this wiki
        this.contextInitializer.initialize(webJarsResourceReference.getWikiId());

        if (!shouldBrowserUseCachedContent(webJarsResourceReference)) {
            // If we get here then either the resource is not cached by the browser or the resource is dynamic.
            InputStream resourceStream = getResourceStream(webJarsResourceReference);

            if (resourceStream != null) {
                try {
                    serveResource(webJarsResourceReference, resourceStream);
                } catch (ResourceReferenceHandlerException e) {
                    this.logger.error(e.getMessage(), e);
                    sendError(HttpStatus.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                }
            } else {
                sendError(HttpStatus.SC_NOT_FOUND, "Resource not found [%s].",
                    getResourceName(webJarsResourceReference));
            }
        }

        // Be a good citizen, continue the chain, in case some lower-priority Handler has something to do for this
        // Resource Reference.
        chain.handleNext(webJarsResourceReference);
    }

    /**
     * @param resourceReference a reference to a WebJar resource
     * @return {@code true} if the referenced resource is static and is cached by the browser, {@code false} if the
     *         browser should discard the cached version and use the new version from this response
     */
    private boolean shouldBrowserUseCachedContent(WebJarsResourceReference resourceReference)
    {
        // If the request contains an "If-Modified-Since" header and the referenced resource is not supposed to be
        // evaluated (i.e. no Velocity code) then return a 304 so to tell the browser to use its cached version.
        Request request = this.container.getRequest();
        if (request instanceof ServletRequest && !shouldEvaluateResource(resourceReference)) {
            // This is a request for a static resource from a WebJar.
            if (((ServletRequest) request).getHttpServletRequest().getHeader("If-Modified-Since") != null) {
                // The user probably used F5 to reload the page and the browser checks if there are changes.
                Response response = this.container.getResponse();
                if (response instanceof ServletResponse) {
                    // Return the 304 Not Modified. Static WebJar resources don't change if their path doesn't change
                    // (and the WebJar version is included in the path).
                    ((ServletResponse) response).getHttpServletResponse().setStatus(
                        HttpServletResponse.SC_NOT_MODIFIED);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param resourceReference the reference to the WebJar resource to get
     * @return the stream that can be used to read the resource from the WebJar
     */
    private InputStream getResourceStream(WebJarsResourceReference resourceReference)
    {
        String resourcePath = String.format("%s%s", WEBJARS_RESOURCE_PREFIX, getResourceName(resourceReference));
        return getClassLoader().getResourceAsStream(resourcePath);
    }

    /**
     * @param resourceReference the reference to the WebJar resource to get
     * @return the name of the specified resource, e.g. "requirejs/2.1.15/require.min.js"
     */
    private String getResourceName(WebJarsResourceReference resourceReference)
    {
        return resourceReference.getResourceName();
    }

    /**
     * @return the Class Loader from which to look for WebJars resources
     */
    protected ClassLoader getClassLoader()
    {
        // Load the resource from the context class loader in order to support WebJars located in XWiki Extensions
        // loaded by the Extension Manager.
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * Sends back the specified resource.
     *
     * @param resourceReference the reference to the WebJar resource to get
     * @param rawResourceStream the resource stream used to read the resource from the WebJar
     * @throws ResourceReferenceHandlerException if it fails to read the resource
     */
    private void serveResource(WebJarsResourceReference resourceReference, InputStream rawResourceStream)
        throws ResourceReferenceHandlerException
    {
        InputStream resourceStream = rawResourceStream;
        String resourceName = getResourceName(resourceReference);

        if (shouldEvaluateResource(resourceReference)) {
            resourceStream = evaluate(resourceName, resourceStream);
        }

        // Make sure the resource stream supports mark & reset which is needed in order be able to detect the
        // content type without affecting the stream (Tika may need to read a few bytes from the start of the
        // stream, in which case it will mark & reset the stream).
        if (!resourceStream.markSupported()) {
            resourceStream = new BufferedInputStream(resourceStream);
        }

        try {
            Response response = this.container.getResponse();
            setResponseHeaders(response, resourceReference);
            response.setContentType(this.tika.detect(resourceStream, resourceName));
            IOUtils.copy(resourceStream, response.getOutputStream());
        } catch (Exception e) {
            throw new ResourceReferenceHandlerException(String.format("Failed to read resource [%s]", resourceName), e);
        } finally {
            IOUtils.closeQuietly(resourceStream);
        }
    }

    /**
     * @param resourceReference a resource reference
     * @return {@code true} if the resource should be evaluated (e.g. if the resource has Velocity code), {@code false}
     *         otherwise
     */
    private boolean shouldEvaluateResource(WebJarsResourceReference resourceReference)
    {
        return Boolean.valueOf(resourceReference.getParameterValue("evaluate"));
    }

    /**
     * Evaluates the given resource using Velocity.
     * 
     * @param resourceName the resource name, useful for debugging in case the evaluation fails
     * @param resourceStream the resource stream used to read the resource from the WebJar
     * @return the result of the evaluation
     * @throws ResourceReferenceHandlerException if the evaluation fails
     */
    private InputStream evaluate(String resourceName, InputStream resourceStream)
        throws ResourceReferenceHandlerException
    {
        try {
            StringWriter writer = new StringWriter();
            this.velocityManager.getVelocityEngine().evaluate(this.velocityManager.getVelocityContext(), writer,
                resourceName, new InputStreamReader(resourceStream, UTF8));
            return new ByteArrayInputStream(writer.toString().getBytes(UTF8));
        } catch (Exception e) {
            throw new ResourceReferenceHandlerException(String.format(
                "Failed to evaluate the Velocity code from WebJar resource [%s]", resourceName), e);
        }
    }

    /**
     * Sets the response headers needed to cache the resource permanently, if the resource is static.
     * 
     * @param response the response
     * @param resourceReference the resource that is being served
     */
    private void setResponseHeaders(Response response, WebJarsResourceReference resourceReference)
    {
        // If the resource contains Velocity code then this code must be evaluated on each request and so the resource
        // must not be cached. Otherwise, if the resource is static we can cache it permanently because the resource
        // version is included in the URL.
        if (response instanceof ServletResponse && !shouldEvaluateResource(resourceReference)) {
            HttpServletResponse httpResponse = ((ServletResponse) response).getHttpServletResponse();
            httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "public");
            httpResponse.setDateHeader(HttpHeaders.EXPIRES, new Date().getTime() + CACHE_DURATION);
            // Even if the resource is cached permanently, most browsers are still sending a request if the user reloads
            // the page using F5. We send back the "Last-Modified" header in the response so that the browser will send
            // us an "If-Modified-Since" request for any subsequent call for this static resource. When this happens we
            // return a 304 to tell the browser to use its cached version.
            httpResponse.setDateHeader(HttpHeaders.LAST_MODIFIED, new Date().getTime());
        }
    }

    /**
     * Sends back the specified status code with the given message in order for the browser to know the resource
     * couldn't be served. This is especially important as we don't want to cache an empty response.
     * 
     * @param statusCode the response status code to send
     * @param message the error message
     * @param parameters the message parameters
     * @throws ResourceReferenceHandlerException if setting the response status code fails
     */
    private void sendError(int statusCode, String message, Object... parameters)
        throws ResourceReferenceHandlerException
    {
        Response response = this.container.getResponse();
        if (response instanceof ServletResponse) {
            HttpServletResponse httpResponse = ((ServletResponse) response).getHttpServletResponse();
            try {
                httpResponse.sendError(statusCode, String.format(message, parameters));
            } catch (IOException e) {
                throw new ResourceReferenceHandlerException(String.format("Failed to return status code [%s].",
                    statusCode), e);
            }
        }
    }

    private void setupContext(String wikiId) throws ResourceReferenceHandlerException
    {

    }
}
