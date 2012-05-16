/*
 * Copyright 2009, Strategic Gains, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
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
package com.strategicgains.restexpress.pipeline;

import static com.strategicgains.restexpress.ContentType.TEXT_PLAIN;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

import java.util.ArrayList;
import java.util.List;

import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import com.strategicgains.restexpress.Request;
import com.strategicgains.restexpress.Response;
import com.strategicgains.restexpress.exception.BadRequestException;
import com.strategicgains.restexpress.exception.ExceptionMapping;
import com.strategicgains.restexpress.exception.ExceptionUtils;
import com.strategicgains.restexpress.exception.ServiceException;
import com.strategicgains.restexpress.response.DefaultHttpResponseWriter;
import com.strategicgains.restexpress.response.HttpResponseWriter;
import com.strategicgains.restexpress.response.ResponseProcessor;
import com.strategicgains.restexpress.response.ResponseProcessorResolver;
import com.strategicgains.restexpress.route.Action;
import com.strategicgains.restexpress.route.RouteResolver;
import com.strategicgains.restexpress.util.HttpSpecification;

/**
 * @author toddf
 * @since Nov 13, 2009
 */
@Sharable
public class DefaultRequestHandler
extends SimpleChannelUpstreamHandler
{
	// SECTION: INSTANCE VARIABLES

	private RouteResolver routeResolver;
	private ResponseProcessorResolver responseProcessorResolver;
	private HttpResponseWriter responseWriter;
	private List<Preprocessor> preprocessors = new ArrayList<Preprocessor>();
	private List<Postprocessor> postprocessors = new ArrayList<Postprocessor>();
	private ExceptionMapping exceptionMap = new ExceptionMapping();
	private List<MessageObserver> messageObservers = new ArrayList<MessageObserver>();


	// SECTION: CONSTRUCTORS

	public DefaultRequestHandler(RouteResolver routeResolver, ResponseProcessorResolver responseProcessorResolver)
	{
		this(routeResolver, responseProcessorResolver, new DefaultHttpResponseWriter());
	}

	public DefaultRequestHandler(RouteResolver routeResolver, ResponseProcessorResolver responseProcessorResolver,
		HttpResponseWriter responseWriter)
	{
		super();
		this.routeResolver = routeResolver;
		this.responseProcessorResolver = responseProcessorResolver;
		setResponseWriter(responseWriter);
	}


	// SECTION: MUTATORS
	
	public void addMessageObserver(MessageObserver... observers)
	{
		for (MessageObserver observer : observers)
		{
			if (!messageObservers.contains(observer))
			{
				messageObservers.add(observer);
			}
		}
	}

	public <T extends Throwable, U extends ServiceException> DefaultRequestHandler mapException(Class<T> from, Class<U> to)
	{
		exceptionMap.map(from, to);
		return this;
	}
	
	public DefaultRequestHandler setExceptionMap(ExceptionMapping map)
	{
		this.exceptionMap = map;
		return this;
	}

	public HttpResponseWriter getResponseWriter()
	{
		return this.responseWriter;
	}

	public void setResponseWriter(HttpResponseWriter writer)
	{
		this.responseWriter = writer;
	}


	// SECTION: SIMPLE-CHANNEL-UPSTREAM-HANDLER

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent event)
	throws Exception
	{
		MessageContext context = createInitialContext(ctx, event);

		try
		{
			notifyReceived(context);
			resolveRoute(context);
			resolveResponseProcessor(context, false);
			invokePreprocessors(context.getRequest());
			Object result = context.getAction().invoke(context.getRequest(), context.getResponse());
	
			if (result != null)
			{
				context.getResponse().setBody(result);
			}
	
			invokePostprocessors(context.getRequest(), context.getResponse());
			serializeResponse(context);
			enforceHttpSpecification(context);
			writeResponse(ctx, context);
			notifySuccess(context);
		}
		catch(Throwable t)
		{
			handleRestExpressException(ctx, t);
		}
		finally
		{
			notifyComplete(context);
		}
	}

	/**
     * @param context
     */
    private void enforceHttpSpecification(MessageContext context)
    {
    	HttpSpecification.enforce(context.getResponse());
    }

	private void handleRestExpressException(ChannelHandlerContext ctx, Throwable cause)
	throws Exception
	{
		MessageContext context = (MessageContext) ctx.getAttachment();
		resolveResponseProcessor(context, true);
		Throwable rootCause = mapServiceException(cause);
		
		if (rootCause != null) // was/is a ServiceException
		{
			context.setHttpStatus(((ServiceException) rootCause).getHttpStatus());
			
			if (ServiceException.class.isAssignableFrom(rootCause.getClass()))
			{
				((ServiceException) rootCause).augmentResponse(context.getResponse());
			}
		}
		else
		{
			rootCause = ExceptionUtils.findRootCause(cause);
			context.setHttpStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
		}

		context.setException(rootCause);
		notifyException(context);
		serializeResponse(context);
		writeResponse(ctx, context);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent event)
	throws Exception
	{
		try
		{
			MessageContext messageContext = (MessageContext) ctx.getAttachment();
			
			if (messageContext != null)
			{
				messageContext.setException(event.getCause());
				notifyException(messageContext);
			}
		}
		catch(Throwable t)
		{
			System.err.print("DefaultRequestHandler.exceptionCaught() threw an exception.");
			t.printStackTrace();
		}
		finally
		{
			event.getChannel().close();
		}
	}

	private MessageContext createInitialContext(ChannelHandlerContext ctx, MessageEvent event)
	{
		Request request = createRequest((HttpRequest) event.getMessage(), ctx);
		Response response = createResponse();
		MessageContext context = new MessageContext(request, response);
		ctx.setAttachment(context);
		return context;
	}

	/**
	 * Resolve the ResponseProcessor based on the requested format (or the default, if none supplied).
	 * If 'shouldUseDefault' is false, a BadRequestException is thrown when the format does not resolve.
	 * Otherwise, if 'shouldUseDefault' is true, the default format is used when the format does not resolve.
	 * 
	 * @param context the message context.
	 * @param shouldUseDefault use the default format when requested format doesn't resolve.
	 * @throws BadRequestException if requested format is not resolved and 'shouldUseDefault' is false.
	 */
	private void resolveResponseProcessor(MessageContext context, boolean shouldUseDefault)
	{
		if (context.hasResponseProcessor()) return;

		ResponseProcessor rp = responseProcessorResolver.resolve(context.getRequest());
		
		if (rp == null)
		{
			if (shouldUseDefault)
			{
				rp = responseProcessorResolver.getDefault();
			}
			else
			{
				throw new BadRequestException("Requested representation format not supported: " + context.getRequest().getFormat());
			}
		}

		context.setResponseProcessor(rp);
	}

	private void resolveRoute(MessageContext context)
    {
	    Action action = routeResolver.resolve(context.getRequest());
		context.setAction(action);
    }


    /**
     * @param request
     * @param response
     */
    private void notifyReceived(MessageContext context)
    {
    	for (MessageObserver observer : messageObservers)
    	{
    		observer.onReceived(context.getRequest(), context.getResponse());
    	}
    }

	/**
     * @param request
     * @param response
     */
    private void notifyComplete(MessageContext context)
    {
    	for (MessageObserver observer : messageObservers)
    	{
    		observer.onComplete(context.getRequest(), context.getResponse());
    	}
    }

	// SECTION: UTILITY -- PRIVATE

	/**
     * @param exception
     * @param request
     * @param response
     */
    private void notifyException(MessageContext context)
    {
    	Throwable exception = context.getException();

    	for (MessageObserver observer : messageObservers)
    	{
    		observer.onException(exception, context.getRequest(), context.getResponse());
    	}
    }

	/**
     * @param request
     * @param response
     */
    private void notifySuccess(MessageContext context)
    {
    	for (MessageObserver observer : messageObservers)
    	{
    		observer.onSuccess(context.getRequest(), context.getResponse());
    	}
    }
	
	public void addPreprocessor(Preprocessor handler)
	{
		if (!preprocessors.contains(handler))
		{
			preprocessors.add(handler);
		}
	}

	public void addPostprocessor(Postprocessor handler)
	{
		if (!postprocessors.contains(handler))
		{
			postprocessors.add(handler);
		}
	}

    private void invokePreprocessors(Request request)
    {
		for (Preprocessor handler : preprocessors)
		{
			handler.process(request);
		}

		request.getBody().resetReaderIndex();
    }

    private void invokePostprocessors(Request request, Response response)
    {
		for (Postprocessor handler : postprocessors)
		{
			handler.process(request, response);
		}
    }

	/**
	 * Uses the exceptionMap to map a Throwable to a ServiceException, if possible.
	 * 
	 * @param cause
	 * @return Either a ServiceException or the root cause of the exception.
	 */
	private Throwable mapServiceException(Throwable cause)
    {
		if (ServiceException.isAssignableFrom(cause))
		{
			return cause;
		}
			
		return exceptionMap.getExceptionFor(cause);
    }

	/**
     * @param request
     * @return
     */
    private Request createRequest(HttpRequest request, ChannelHandlerContext context)
    {
    	return new Request(request, routeResolver);
    }

	/**
     * @param request
     * @return
     */
    private Response createResponse()
    {
    	return new Response();
    }

    /**
     * @param message
     * @return
     */
    private void writeResponse(ChannelHandlerContext ctx, MessageContext context)
    {
    	getResponseWriter().write(ctx, context.getRequest(), context.getResponse());
    }

	private void serializeResponse(MessageContext context)
	{
		Response response = context.getResponse();

		if (shouldSerialize(context))
		{
			response.serialize();
		}

		if (HttpSpecification.isContentTypeAllowed(response))
		{
			if (!response.hasHeader(CONTENT_TYPE))
			{
				String contentType = (context.getContentType() == null ? TEXT_PLAIN : context.getContentType());
				response.addHeader(CONTENT_TYPE, contentType);
			}
		}
	}

    private boolean shouldSerialize(MessageContext context)
    {
    	
        return (context.shouldSerializeResponse() && (responseProcessorResolver != null));
    }
}
