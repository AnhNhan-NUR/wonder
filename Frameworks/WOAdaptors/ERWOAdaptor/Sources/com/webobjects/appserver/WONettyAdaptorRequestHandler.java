package com.webobjects.appserver;

import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.COOKIE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.Cookie;
import org.jboss.netty.handler.codec.http.CookieDecoder;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.logging.CommonsLoggerFactory;
import org.jboss.netty.logging.InternalLogger;

import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDelayedCallbackCenter;
import com.webobjects.foundation.NSMutableDictionary;

/**
 * @author <a href="http://www.jboss.org/netty/">The Netty Project</a>
 * @author Andy Taylor (andy.taylor@jboss.org)
 * @author <a href="http://gleamynode.net/">Trustin Lee</a>
 *
 * @version $Rev: 2288 $, $Date: 2010-05-27 21:40:50 +0900 (Thu, 27 May 2010) $
 */
public class WONettyAdaptorRequestHandler extends SimpleChannelUpstreamHandler {
	
	private static InternalLogger log = CommonsLoggerFactory.getDefaultFactory().newInstance(WONettyAdaptorRequestHandler.class.getName());

	private HttpRequest request;
	private boolean readingChunks;

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws IOException {
		if (!readingChunks) {
			HttpRequest request = this.request = (HttpRequest) e.getMessage();
	        NSMutableDictionary<String, NSArray<String>> headers = new NSMutableDictionary<String, NSArray<String>>();
	        for (Map.Entry<String, String> header: request.getHeaders()) {
	        	headers.setObjectForKey(new NSArray<String>(header.getValue().split(",")), header.getKey());
	        }

			if (request.isChunked()) {
				readingChunks = true;
			} else {
				ChannelBuffer content = request.getContent();
				NSData contentData = NSData.EmptyData;
				if (content.readable()) {
					contentData = new NSData(ChannelBuffers.copiedBuffer(content).array());
				}
		        WORequest worequest = WOApplication.application().createRequest(
		        		request.getMethod().getName(), 
		        		request.getUri(), 
		        		request.getProtocolVersion().getText(), 
		        		headers,
		        		contentData, 
		        		null);
		        
	            if (worequest != null) {
	                WOResponse woresponse = WOApplication.application().dispatchRequest(worequest);
	                NSDelayedCallbackCenter.defaultCenter().eventEnded();
	                
					writeResponse(woresponse, e);
	            }
			}
		} 
		// TODO form data
		/* else {
			HttpChunk chunk = (HttpChunk) e.getMessage();
			if (chunk.isLast()) {
				readingChunks = false;
				buf.append("END OF CONTENT\r\n");

				HttpChunkTrailer trailer = (HttpChunkTrailer) chunk;
				if (!trailer.getHeaderNames().isEmpty()) {
					buf.append("\r\n");
					for (String name: trailer.getHeaderNames()) {
						for (String value: trailer.getHeaders(name)) {
							buf.append("TRAILING HEADER: " + name + " = " + value + "\r\n");
						}
					}
					buf.append("\r\n");
				}

				writeResponse(e);
			} else {
				buf.append("CHUNK: " + chunk.getContent().toString(CharsetUtil.UTF_8) + "\r\n");
			}
		} */
	}

	private void writeResponse(WOResponse woresponse, MessageEvent e) throws IOException {
		// Decide whether to close the connection or not.
		boolean keepAlive = isKeepAlive(request);

		// Build the response object.
		HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
		
		// get content from woresponse
		int length = woresponse._contentLength();
		if (length > 0) {
			if (!"".equals(woresponse._content.toString())) {
				response.setContent(ChannelBuffers.copiedBuffer(woresponse._content.toString(), woresponse.contentEncoding()));
			} else
				response.setContent(ChannelBuffers.copiedBuffer(woresponse._contentData._bytesNoCopy()));
		} else if (woresponse.contentInputStream() != null) {
			ByteBuffer buffer = ByteBuffer.allocate(woresponse.contentInputStreamBufferSize());
			length = woresponse.contentInputStream().read(buffer.array());
			response.setContent(ChannelBuffers.copiedBuffer(buffer));
		}
		String contentType = woresponse.headerForKey(CONTENT_TYPE);
		response.setHeader(CONTENT_TYPE, contentType);

		if (keepAlive) {
			// Add 'Content-Length' header only for a keep-alive connection.
			response.setHeader(CONTENT_LENGTH, length);
		}

		// Encode the cookie.
		String cookieString = request.getHeader(COOKIE);
		if (cookieString != null) {
			CookieDecoder cookieDecoder = new CookieDecoder();
			Set<Cookie> cookies = cookieDecoder.decode(cookieString);
			if(!cookies.isEmpty()) {
				// Reset the cookies if necessary.
				CookieEncoder cookieEncoder = new CookieEncoder(true);
				for (Cookie cookie : cookies) {
					cookieEncoder.addCookie(cookie);
				}
				response.addHeader(SET_COOKIE, cookieEncoder.encode());
			}
		}

		// Write the response.
		ChannelFuture future = e.getChannel().write(response);

		// Close the non-keep-alive connection after the write operation is done.
		if (!keepAlive) {
			future.addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
		log.error("Exception caught", e.getCause());
		e.getChannel().close();
	}
}