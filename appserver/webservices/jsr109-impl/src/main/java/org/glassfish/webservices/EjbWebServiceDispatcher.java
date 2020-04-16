/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.webservices;

import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletContext;
import javax.xml.soap.MimeHeaders;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPException;

import javax.xml.namespace.QName;
import com.sun.xml.messaging.saaj.util.ByteInputStream;
import com.sun.xml.rpc.spi.JaxRpcObjectFactory;
import com.sun.xml.rpc.spi.runtime.SOAPMessageContext;
import com.sun.xml.rpc.spi.runtime.SOAPConstants;
import com.sun.xml.rpc.spi.runtime.StreamingHandler;
import java.text.MessageFormat;

import org.glassfish.webservices.monitoring.*;

import java.util.logging.Logger;
import java.util.logging.Level;
import org.glassfish.api.invocation.ComponentInvocation;
import org.glassfish.internal.api.Globals;
import org.glassfish.ejb.api.EJBInvocation;

/**
 * Handles dispatching of ejb web service http invocations.
 *
 * @author Kenneth Saks
 */
public class EjbWebServiceDispatcher implements EjbMessageDispatcher {

    private static final Logger logger = LogUtils.getLogger();

    private JaxRpcObjectFactory rpcFactory;
    private final WsUtil wsUtil = new WsUtil();
    private WebServiceEngineImpl wsEngine;

    // @@@ This should be added to jaxrpc spi, probably within
    // SOAPConstants.
    private static final QName FAULT_CODE_CLIENT =
        new QName(SOAPConstants.URI_ENVELOPE, "Client");

    // @@@ Used to set http servlet response object so that TieBase
    // will correctly flush response code for one-way operations.
    // Should be added to SPI
    private static final String HTTP_SERVLET_RESPONSE = 
        "com.sun.xml.rpc.server.http.HttpServletResponse";

    //the security service
    org.glassfish.webservices.SecurityService  secServ;

    public EjbWebServiceDispatcher() {
        rpcFactory = JaxRpcObjectFactory.newInstance();
        wsEngine = WebServiceEngineImpl.getInstance();
        if (Globals.getDefaultHabitat() != null) {
            secServ = Globals.get(org.glassfish.webservices.SecurityService.class);
        }
    }           

    @Override
    public void invoke(HttpServletRequest req, 
                       HttpServletResponse resp,
                       ServletContext ctxt,
                       EjbRuntimeEndpointInfo endpointInfo) {

        String method = req.getMethod();
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogUtils.WEBSERVICE_DISPATCHER_INFO,
                    new Object[] {req.getMethod(), req.getRequestURI(), req.getQueryString()});
        }
        try {
            if( method.equals("POST") ) {
                handlePost(req, resp, endpointInfo);
            } else if( method.equals("GET") ) {
                handleGet(req, resp, ctxt, endpointInfo);
            } else {
                String errorMessage = MessageFormat.format(
                        logger.getResourceBundle().getString(LogUtils.UNSUPPORTED_METHOD_REQUEST),
                        new Object[] {method, endpointInfo.getEndpoint().getEndpointName(),
                            endpointInfo.getEndpointAddressUri()});
                logger.log(Level.WARNING, errorMessage);
                wsUtil.writeInvalidMethodType(resp, errorMessage);
            }
        } catch(Exception e) {
            logger.log(Level.WARNING, LogUtils.EJB_ENDPOINT_EXCEPTION, e);
        }
    }

    private void handlePost(HttpServletRequest req,
                            HttpServletResponse resp,
                            EjbRuntimeEndpointInfo endpointInfo)
        throws IOException, SOAPException {
        
        JAXRPCEndpointImpl endpoint = null;               
        String messageID = null;
        SOAPMessageContext msgContext = null;
        
        try {
            
            MimeHeaders headers = wsUtil.getHeaders(req);
            if (!wsUtil.hasTextXmlContentType(headers)) {
                wsUtil.writeInvalidContentType(resp);
                return;
            }
            
            msgContext = rpcFactory.createSOAPMessageContext();
            SOAPMessage message = createSOAPMessage(req, headers);
                        
    	    boolean wssSucceded = true;
            
            if (message != null) {                                
                
                msgContext.setMessage(message);

                // get the endpoint info
                endpoint = (JAXRPCEndpointImpl) endpointInfo.getEndpoint().getExtraAttribute(EndpointImpl.NAME);
                
                if (endpoint!=null) {
                    // first global notification
                    if (wsEngine.hasGlobalMessageListener()) {
                        messageID = wsEngine.preProcessRequest(endpoint);
                    }
                } else {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, LogUtils.MISSING_MONITORING_INFO, req.getRequestURI());
                    }
                }                                   
                
                AdapterInvocationInfo aInfo = null;
                
                if (!(endpointInfo instanceof Ejb2RuntimeEndpointInfo)) {
                    throw new IllegalArgumentException(endpointInfo + "is not instance of Ejb2RuntimeEndpointInfo.");
                }

                try {
                    Ejb2RuntimeEndpointInfo endpointInfo2 = (Ejb2RuntimeEndpointInfo)endpointInfo;
                    // Do ejb container pre-invocation and pre-handler
                    // logic
                    aInfo = endpointInfo2.getHandlerImplementor();

                    // Set message context in invocation
                    ComponentInvocation inv = aInfo.getInv();
                    if (inv instanceof EJBInvocation)
                        EJBInvocation.class.cast(inv).setMessageContext(msgContext);


                    // Set http response object so one-way operations will
                    // response before actual business method invocation.
                    msgContext.setProperty(HTTP_SERVLET_RESPONSE, resp);
                    if (secServ != null) {
                        wssSucceded = secServ.validateRequest(endpointInfo2.getServerAuthConfig(),
                                (StreamingHandler)aInfo.getHandler(), msgContext);
                    }
                    // Trace if necessary
                    if (messageID!=null || (endpoint!=null && endpoint.hasListeners())) {
                        // create the thread local
                        ThreadLocalInfo threadLocalInfo = new ThreadLocalInfo(messageID, req);
                        wsEngine.getThreadLocal().set(threadLocalInfo);
                        if (endpoint != null) {
                            endpoint.processRequest(msgContext);
                        } else {
                            if (logger.isLoggable(Level.FINE)) {
                                logger.log(Level.FINE, LogUtils.MISSING_MONITORING_INFO, req.getRequestURI());
                            }
                        }
                    }
                    
                    // Pass control back to jaxrpc runtime to invoke
                    // any handlers and call the webservice method itself,
                    // which will be flow back into the ejb container.
                    if (wssSucceded) {
                        aInfo.getHandler().handle(msgContext);
                    }
                } finally {
                    // Always call release, even if an error happened
                    // during getImplementor(), since some of the
                    // preInvoke steps might have occurred.  It's ok
                    // if implementor is null.
                    if (aInfo != null) {
                        endpointInfo.releaseImplementor(aInfo.getInv());
                    }
                }
            } else {
                String errorMsg = MessageFormat.format(
                        logger.getResourceBundle().getString(LogUtils.NULL_MESSAGE),
                        endpointInfo.getEndpoint().getEndpointName(), endpointInfo.getEndpointAddressUri());
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine(errorMsg);
                    }
                    msgContext.writeSimpleErrorResponse
                    (FAULT_CODE_CLIENT, errorMsg);
            }
            if (messageID!=null || endpoint!=null) {
                endpoint.processResponse(msgContext);
            }
            SOAPMessage reply = msgContext.getMessage();
            if (secServ != null && wssSucceded) {
                if (!(endpointInfo instanceof Ejb2RuntimeEndpointInfo)) {
                    throw new IllegalArgumentException(endpointInfo + "is not instance of Ejb2RuntimeEndpointInfo.");
                }

                Ejb2RuntimeEndpointInfo endpointInfo2 = (Ejb2RuntimeEndpointInfo)endpointInfo;
                secServ.secureResponse(endpointInfo2.getServerAuthConfig(),(StreamingHandler)endpointInfo2.getHandlerImplementor().getHandler(),msgContext);
            }
            
            if (reply.saveRequired()) {
                reply.saveChanges();
            }
            wsUtil.writeReply(resp, msgContext);
        } catch (Throwable e) {
            String errorMessage = MessageFormat.format(
                    logger.getResourceBundle().getString(LogUtils.ERROR_ON_EJB),
                    new Object[] {endpointInfo.getEndpoint().getEndpointName(),
                        endpointInfo.getEndpointAddressUri(), e.getMessage()});
            logger.log(Level.WARNING, errorMessage, e);
            SOAPMessageContext errorMsgContext =
                rpcFactory.createSOAPMessageContext();
            errorMsgContext.writeSimpleErrorResponse
                (SOAPConstants.FAULT_CODE_SERVER, errorMessage);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            if (messageID!=null || endpoint!=null) {
                endpoint.processResponse(errorMsgContext);
            }
            wsUtil.writeReply(resp, errorMsgContext);
        }

        // final tracing notification
        if (messageID!=null) {
            HttpResponseInfoImpl response = new HttpResponseInfoImpl(resp);
            wsEngine.postProcessResponse(messageID, response);
        }
    }

    private void handleGet(HttpServletRequest req, 
                           HttpServletResponse resp,
                           ServletContext ctxt,
                           EjbRuntimeEndpointInfo endpointInfo)
        throws IOException
    {
       
        wsUtil.handleGet(req, resp, endpointInfo.getEndpoint());           

    }    

    protected SOAPMessage createSOAPMessage(HttpServletRequest request,
                                            MimeHeaders headers)
        throws IOException {

        InputStream is = request.getInputStream();
        
        byte[] bytes = readFully(is);
        int length = request.getContentLength() == -1 ? bytes.length 
            : request.getContentLength();
        ByteInputStream in = new ByteInputStream(bytes, length);

        SOAPMessageContext msgContext = rpcFactory.createSOAPMessageContext();
        SOAPMessage message = msgContext.createMessage(headers, in);

        return message;
    }

    protected byte[] readFully(InputStream istream) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int num = 0;
        while( (num = istream.read(buf)) != -1) {
            bout.write(buf, 0, num);
        }
        byte[] ret = bout.toByteArray();
        return ret;
    }
}
