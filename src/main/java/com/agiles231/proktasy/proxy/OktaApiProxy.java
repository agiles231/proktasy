package com.agiles231.proktasy.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.impl.client.HttpClientBuilder;
import org.mitre.dsmiley.httpproxy.ProxyServlet;

public class OktaApiProxy extends ProxyServlet {
	
	private static Long TIME_EPSILON_MILLIS = 5000l; // five seconds, used for time disrepancy between local server and okta
	private static Integer BUFFER_REMAINING = 5;
	private static Integer BUFFER_CONCURRENCY = 5;
	private static Integer MAX_CONCURRENT_REQUESTS;
	private static String TARGET_HOST_URI;
	private static String PROKTASY_HOST_URI;

	private static String PROXY_HOST;
	private static Integer PROXY_PORT;

	private static Map<String, Integer> currentRequests;
	private static Map<String, Integer> rateLimitRemainings;
	private static Map<String, Instant> rateLimitResets;
	private static Map<String, Thread> rateLimitFetchThread;
	private static Map<String, Queue<Thread>> waitingThreads; // threads waiting on more info, or waiting on reset
	private static Queue<Thread> concurrentWaitingThreads; // threads waiting due to concucrrency
	
	private static Object lock;

	@Override
	public void init(ServletConfig config) throws ServletException {
		lock = new Object();
		currentRequests = new HashMap<>();
		rateLimitRemainings = new HashMap<>();
		rateLimitResets = new HashMap<>();
		rateLimitFetchThread = new HashMap<>();
		waitingThreads = new HashMap<>();
		concurrentWaitingThreads = new LinkedList<>();

		String maxConcurrentRequests = config.getInitParameter("proktasy.maxConcurrentRequests");
		if (maxConcurrentRequests != null && maxConcurrentRequests.matches("\\d+")) {
			MAX_CONCURRENT_REQUESTS = Integer.parseInt(maxConcurrentRequests);
		}
		PROXY_HOST = config.getInitParameter("proktasy.proxyHost");
		String proxyPort = config.getInitParameter("proktasy.proxyPort");
		if (proxyPort != null && proxyPort.matches("\\d+")) {
			PROXY_PORT = Integer.parseInt(proxyPort);
		}

		// initialize properties
		String bufferRemaining = config.getInitParameter("proktasy.bufferRequests");
		if (bufferRemaining != null && bufferRemaining.matches("\\d+")) {
			BUFFER_REMAINING = Integer.parseInt(bufferRemaining);
		}
		String bufferConcurrency = config.getInitParameter("proktasy.bufferConcurrency");
		if (bufferConcurrency != null && bufferConcurrency.matches("\\d+")) {
			BUFFER_CONCURRENCY = Integer.parseInt(bufferConcurrency);
		}
		TARGET_HOST_URI = config.getInitParameter("proktasy.targetHost");
		PROKTASY_HOST_URI = config.getInitParameter("proktasy.proktasyHost");
		String[] rateLimitRegexes = config.getInitParameter("proktasy.rateLimitRegexes").split(",");
		for (String rateLimitRegex : rateLimitRegexes) {
			currentRequests.put(rateLimitRegex, 0);
			rateLimitRemainings.put(rateLimitRegex, 1);
			rateLimitResets.put(rateLimitRegex, Instant.now().minus(Duration.ofMinutes(5))); // set as 5 minutes in past
			rateLimitFetchThread.put(rateLimitRegex, null);
			waitingThreads.put(rateLimitRegex, new LinkedList<>());
		}

		super.init(config);
		// reconfigure some stuff that won't be correctly configured by parent class
		this.targetUri = TARGET_HOST_URI;
		URI targetUriObj = null;
		try {
			targetUriObj = new URI(targetUri);
		} catch (URISyntaxException e) {
			throw new ServletException(e);
		}
		this.targetHost = URIUtils.extractHost(targetUriObj);
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		Thread thisThread = Thread.currentThread();
		String rateLimitRegex = getMatchingApiEndpointRegex(req); // get which rate limit bucket this request falls into
		boolean executed = false; // has it executed yet? only executes once
		boolean isFetchThread = false; // is this thread the thread that will fetch new info in event it is needed (i.e. reset is in past and remaining info is expired)
		boolean fetchNeeded = false;
		boolean tooManyConcurrentRequests = false;
		boolean requestAdded = false;
		try {
			while (!executed) {
				boolean execute = false;
				Instant existingReset = null;
				Instant now = null;
				Integer existingRemaining = null;
				Integer currentReqs = null;
				Integer totalConcurrentRequests = null;
				boolean noneRemaining = false;
				synchronized(lock) { // lock for entire period before request when figuring out what state is
					if (!isFetchThread && fetchNeeded) { // because of init state, we know these were set from previous iteration and that we mustve slept/waited
						fetchNeeded = false; // assume false now; if true, we will figure out later
						Queue<Thread> threads = waitingThreads.get(rateLimitRegex); // remove from waiting threads
						threads.remove(thisThread);
					}
					if (tooManyConcurrentRequests) { // because of init state, we know these were set from previous iteration and that we mustve slept/waited
						tooManyConcurrentRequests = false; // assume false now; if true, we will figure out later
						concurrentWaitingThreads.remove(thisThread); // remove from concurrent waiting threads
					}
					existingReset = rateLimitResets.get(rateLimitRegex); // last known reset time
					now = Instant.now();
					existingRemaining = rateLimitRemainings.get(rateLimitRegex); // last known "remaining" for rate limit bucket
					currentReqs = currentRequests.get(rateLimitRegex); // all currently executing requests
					totalConcurrentRequests = currentRequests.values().stream().mapToInt(v -> v).sum();
					if (totalConcurrentRequests >= MAX_CONCURRENT_REQUESTS - BUFFER_CONCURRENCY) {
						concurrentWaitingThreads.add(thisThread);
						tooManyConcurrentRequests = true;
					} else if (existingReset.isBefore(now)){ // last known rest is in the past, so determine new reset
						fetchNeeded = true;
						Thread fetchThread = null;
						fetchThread = rateLimitFetchThread.get(rateLimitRegex);
						if (fetchThread == null) { // no thread is fetching new reset, so let this be the one
							isFetchThread = true;
							execute = true;
							currentRequests.compute(rateLimitRegex, (key, value) -> value + 1);
							requestAdded = true;
							rateLimitFetchThread.put(rateLimitRegex, Thread.currentThread()); // set fetching thread to this
						}
						if (!isFetchThread) { // another thread is determining new reset, so wait until figured out
							Queue<Thread> threads = waitingThreads.get(rateLimitRegex);
							threads.add(thisThread);
						}
					} else if (existingRemaining - currentReqs > BUFFER_REMAINING) { // reset in future, remaining left so go for it
						execute = true;
						currentRequests.compute(rateLimitRegex, (key, value) -> value + 1);
						requestAdded = true;
					} else if (existingRemaining - currentReqs <= BUFFER_REMAINING) { // no more remaining, so wait until reset then re-evaluate
						noneRemaining = true;
						
					}
				}
				if (noneRemaining) {
					try {
						long timeToSleep = Duration.between(Instant.now(), existingReset).toMillis() + TIME_EPSILON_MILLIS;
						System.out.println("Sleeping for " + timeToSleep + " for rate limiting...");
						thisThread.sleep(timeToSleep);
						
					} catch (InterruptedException e) {
						throw new ServletException(e);
					}
				}
				if ((!isFetchThread && fetchNeeded) || tooManyConcurrentRequests) {
					try {
						synchronized(thisThread) {
							String reason = tooManyConcurrentRequests ? "concurrency" : "fetch";
							System.out.println("Waiting for up to 60 seconds due to " + reason + "; another thread may wake us up before then...");
							thisThread.wait(60000l); // max wait of 60 seconds
						}
					} catch (InterruptedException e) {
						throw new ServletException(e);
					}
				}
				if (execute) {
					super.service(req, resp);
					executed = true;
					System.out.println("Response status: " + resp.getStatus());
					String remainingHeader = resp.getHeader("X-Rate-Limit-Remaining");
					String resetUtcEpoch = resp.getHeader("X-Rate-Limit-Reset");
					Instant reset = Instant.ofEpochSecond(Long.parseLong(resetUtcEpoch));
					Integer remaining = Integer.parseInt(remainingHeader);
					synchronized(lock) {
						existingReset = rateLimitResets.get(rateLimitRegex); // fetch fresh
						existingRemaining = rateLimitRemainings.get(rateLimitRegex);
						totalConcurrentRequests = currentRequests.values().stream().mapToInt(v -> v).sum();
						if (requestAdded) {
							currentRequests.compute(rateLimitRegex, (key, value) -> value - 1);
							requestAdded = false;
						}
						if (concurrentWaitingThreads.size() > 0) { // if a thread is waiting due to concurrency, then we will wake it since this just completed
							Thread waitingThread = concurrentWaitingThreads.poll();
							if (waitingThread != null) {
								synchronized(waitingThread) {
									waitingThread.notify(); // notify the first queued waiting thread
								}
							}
						}
						if (existingReset.isBefore(reset)) { // always take furthest out as it is newer info
							rateLimitResets.put(rateLimitRegex, reset);
							rateLimitRemainings.put(rateLimitRegex, remaining);
						} else { // not new reset, so assume lowest remaining is the newest info (requests do not necessarily complete in perfect order)
							rateLimitRemainings.put(rateLimitRegex, Math.min(remaining, existingRemaining));
						}
					}

				
					if (isFetchThread) {
						synchronized(lock) {
							rateLimitFetchThread.remove(rateLimitRegex); // remove this as fetching thread
							isFetchThread = false;
							// notify waiting threads
							Queue<Thread> threads = waitingThreads.get(rateLimitRegex);
							for (Thread thread : threads) {
								synchronized(thread) {
									thread.notify();
								}
							}
							threads.clear(); // remove them all since they are not waiting anymore
						}
					}
				}
			}
		} catch (Exception e) {
			// neutralize effects from this thread on shared state
			synchronized(lock) {
				Queue<Thread> threads = waitingThreads.get(rateLimitRegex);
				if (isFetchThread) {
					isFetchThread = false;
					rateLimitFetchThread.remove(rateLimitRegex);
					// notify a single waiting thread so it can become the fetch thread
					Thread thread = !threads.isEmpty() ? threads.poll() : null;
					if(thread != null) {
						synchronized(thread) {
							thread.notify();
						}
					}
				} else if (threads != null && threads.contains(thisThread)) {
					waitingThreads.get(rateLimitRegex).remove(thisThread);
				}

				if (concurrentWaitingThreads.contains(thisThread)) {
					concurrentWaitingThreads.remove(thisThread);
				}
				
				if (requestAdded) {
					currentRequests.compute(rateLimitRegex, (key, value) -> value - 1);
				}
			}
			throw e;
		} finally {
			
		}
	}
	
	@Override
	protected void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse resp,
            HttpRequest proxyRequest, HttpServletRequest servletRequest) throws IOException {
		Collection<String> headerNames = resp.getHeaderNames();
		for (String headerName : headerNames) {
			Collection<String> headers = resp.getHeaders(headerName);
			boolean set = false;
			for (String header : headers) {
				if (!set) {
					resp.setHeader(headerName, header.replace(TARGET_HOST_URI, PROKTASY_HOST_URI));
					set = true;                                              
				} else {                                                     
					resp.addHeader(headerName, header.replace(TARGET_HOST_URI, PROKTASY_HOST_URI));
				}
				
			}
		}
		super.copyResponseEntity(proxyResponse, resp, proxyRequest, servletRequest);
	}
	
	private final String getMatchingApiEndpointRegex(HttpServletRequest req) {
		String path = req.getRequestURI().substring(req.getContextPath().length());
		String rateLimitRegex = null;
		synchronized(lock) {
			rateLimitRegex = rateLimitRemainings.keySet().stream().filter(k -> path.matches(k)).findFirst().orElse(null);
		}
		return rateLimitRegex;
	}
	
	@Override
	protected HttpClient createHttpClient() {
	    HttpClientBuilder clientBuilder = HttpClientBuilder.create()
	                                        .setDefaultRequestConfig(buildRequestConfig())
	                                        .setDefaultSocketConfig(buildSocketConfig());
	    clientBuilder.setProxy(new HttpHost(PROXY_HOST, PROXY_PORT));
	    clientBuilder.setMaxConnTotal(maxConnections);
	    
	    if (useSystemProperties)
	      clientBuilder = clientBuilder.useSystemProperties();
	    return clientBuilder.build();
	  }
}
