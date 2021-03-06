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
package org.xwiki.rest.internal;

import org.restlet.Restlet;
import org.restlet.ext.jaxrs.JaxRsApplication;
import org.restlet.routing.Router;
import org.restlet.routing.Template;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.rest.internal.resources.BrowserAuthenticationResource;

/**
 * <p>
 * This class implements the main Restlet application using the Restlet's JAX-RS extension. The implementation also
 * setups the needed filters for handling the requests (setup/cleanup and authentication filters)
 * </p>
 * 
 * @version $Id$
 */
public class XWikiRestletJaxRsApplication extends JaxRsApplication
{
    @Override
    public Restlet createInboundRoot()
    {
        /* Create the JAX-RS application and add it to the main Restlet JAX-RS application */
        XWikiJaxRsApplication xwikiJaxRsApplication = new XWikiJaxRsApplication(getContext());
        add(xwikiJaxRsApplication);

        /*
         * Create the root restlet. This basically sets up a chain: setup/cleanup filter -> authentication filter ->
         * router
         */
        XWikiSetupCleanupFilter setupCleanupFilter = new XWikiSetupCleanupFilter();

        XWikiAuthentication xwikiAuthentication = new XWikiAuthentication(getContext());
        ComponentManager componentManager = 
            (ComponentManager) getContext().getAttributes().get(Constants.XWIKI_COMPONENT_MANAGER);
        xwikiAuthentication.setVerifier(new XWikiSecretVerifier(getContext(), componentManager));

        /* Create a router for adding resources */
        Router router = new Router();
        router.attach(BrowserAuthenticationResource.URI_PATTERN, BrowserAuthenticationResource.class);
        router.setDefaultMatchingMode(Template.MODE_STARTS_WITH);

        /*
         * Add to the router the restlet generated by the JAX-RS application which takes care of dispatching requests to
         * JAX-RS resources
         */
        Restlet jaxRsRoot = super.createInboundRoot();

        /* Add support for media query parameter for selecting the media type */
        getTunnelService().setEnabled(true);
        getMetadataService().addCommonExtensions();
        getTunnelService().setQueryTunnel(true);

        router.attach(jaxRsRoot);

        /* Build the actual chain */
        setupCleanupFilter.setNext(xwikiAuthentication);
        xwikiAuthentication.setNext(router);

        /* Return the setup/cleanup filter (the entry point for the chain) as the root restlet */
        return setupCleanupFilter;
    }

}
