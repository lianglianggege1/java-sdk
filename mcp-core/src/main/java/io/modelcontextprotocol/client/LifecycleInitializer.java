/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.ContextView;

/**
 * <b>Handles the protocol initialization phase between client and server</b>
 *    处理客户端和服务器之间的协议初始化阶段
 *
 * <p>
 * The initialization phase MUST be the first interaction between client and server.
 * During this phase, the client and server perform the following operations:
 * 初始化阶段必须是客户端和服务器之间的首次交互。在此阶段，客户端和服务器执行以下操作：
 * <ul>
 * <li>Establish protocol version compatibility</li>
 *     建立协议版本兼容性
 * <li>Exchange and negotiate capabilities</li>
 *     交流和协商能力
 * <li>Share implementation details</li>
 *     分享实施细节
 * </ul>
 *
 * <b>Client Initialization Process</b>
 *    客户端初始化过程
 * <p>
 * The client MUST initiate this phase by sending an initialize request containing:
 * 客户端必须通过发送包含以下内容的初始化请求来启动此阶段：
 * <ul>
 * <li>Protocol version supported</li>
 *     支持的协议版本
 * <li>Client capabilities</li>
 *     客户能力
 * <li>Client implementation information</li>
 *     客户实施信息
 * </ul>
 *
 * <p>
 * After successful initialization, the client MUST send an initialized notification to
 * indicate it is ready to begin normal operations.
 * 初始化成功后，客户端必须发送初始化通知，表明它已准备好开始正常操作。
 *
 * <b>Server Response</b>
 *    服务器响应
 * <p>
 * The server MUST respond with its own capabilities and information.
 * 服务器必须提供自身的功能和信息进行响应。
 *
 * <b>Protocol Version Negotiation</b>
 *    协议版本协商
 * <p>
 * In the initialize request, the client MUST send a protocol version it supports. This
 * SHOULD be the latest version supported by the client.
 * 在初始化请求中，客户端必须发送其支持的协议版本。这应该是客户端支持的最新版本。
 *
 * <p>
 * If the server supports the requested protocol version, it MUST respond with the same
 * version. Otherwise, the server MUST respond with another protocol version it supports.
 * This SHOULD be the latest version supported by the server.
 * 如果服务器支持请求的协议版本，则必须返回相同的版本。
 * 否则，服务器必须返回其支持的另一个协议版本。
 * 该版本应该是服务器支持的最新版本。
 *
 * <p>
 * If the client does not support the version in the server's response, it SHOULD
 * disconnect.
 * 如果客户端不支持服务器响应中的版本，则应断开连接。请求限制
 *
 * <b>Request Restrictions</b>
 *    请求限制
 * <p>
 * <strong>Important:</strong> The following restrictions apply during initialization:
 * 初始化期间需遵守以下限制：
 * <ul>
 * <li>The client SHOULD NOT send requests other than pings before the server has
 * responded to the initialize request</li>
 * 在服务器响应初始化请求之前，客户端不应发送除 ping 请求之外的任何请求。
 * <li>The server SHOULD NOT send requests other than pings and logging before receiving
 * the initialized notification</li>
 * 服务器在收到初始化通知之前，不应发送除 ping 和日志记录之外的任何请求。
 * </ul>
 */
class LifecycleInitializer {

	private static final Logger logger = LoggerFactory.getLogger(LifecycleInitializer.class);

	/**
	 * The MCP session supplier that manages bidirectional JSON-RPC communication between
	 * clients and servers.
	 */
	private final Function<ContextView, McpClientSession> sessionSupplier;

	private final McpSchema.ClientCapabilities clientCapabilities;

	private final McpSchema.Implementation clientInfo;

	private List<String> protocolVersions;

	private final AtomicReference<DefaultInitialization> initializationRef = new AtomicReference<>();

	/**
	 * The max timeout to await for the client-server connection to be initialized.
	 */
	private final Duration initializationTimeout;

	/**
	 * Post-initialization hook to perform additional operations after every successful
	 * initialization.
	 */
	private final Function<Initialization, Mono<Void>> postInitializationHook;

	public LifecycleInitializer(McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			List<String> protocolVersions, Duration initializationTimeout,
			Function<ContextView, McpClientSession> sessionSupplier,
			Function<Initialization, Mono<Void>> postInitializationHook) {

		Assert.notNull(sessionSupplier, "Session supplier must not be null");
		Assert.notNull(clientCapabilities, "Client capabilities must not be null");
		Assert.notNull(clientInfo, "Client info must not be null");
		Assert.notEmpty(protocolVersions, "Protocol versions must not be empty");
		Assert.notNull(initializationTimeout, "Initialization timeout must not be null");
		Assert.notNull(postInitializationHook, "Post-initialization hook must not be null");

		this.sessionSupplier = sessionSupplier;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
		this.protocolVersions = Collections.unmodifiableList(new ArrayList<>(protocolVersions));
		this.initializationTimeout = initializationTimeout;
		this.postInitializationHook = postInitializationHook;
	}

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.protocolVersions = protocolVersions;
	}

	/**
	 * Represents the initialization state of the MCP client.
	 * 表示 MCP 客户端的初始化状态。
	 */
	interface Initialization {

		/**
		 * Returns the MCP client session that is used to communicate with the server.
		 * This session is established during the initialization process and is used for
		 * sending requests and notifications.
		 * 返回用于与服务器通信的 MCP 客户端会话。
		 * 此会话在初始化过程中建立，用于发送请求和通知。
		 * @return The MCP client session
		 */
		McpClientSession mcpSession();

		/**
		 * Returns the result of the MCP initialization process. This result contains
		 * information about the protocol version, capabilities, server info, and
		 * instructions provided by the server during the initialization phase.
		 * 返回 MCP 初始化过程的结果。
		 * 该结果包含有关协议版本、功能、服务器信息以及服务器在初始化阶段提供的指令的信息。
		 * @return The result of the MCP initialization process
		 */
		McpSchema.InitializeResult initializeResult();

	}

	private static class DefaultInitialization implements Initialization {

		/**
		 * A sink that emits the result of the MCP initialization process. It allows
		 * subscribers to wait for the initialization to complete.
		 * 一个用于输出 MCP 初始化过程结果的接收器。
		 * 它允许订阅者等待初始化完成。
		 */
		private final Sinks.One<McpSchema.InitializeResult> initSink;

		/**
		 * Holds the result of the MCP initialization process. It is used to cache the
		 * result for future requests.
		 * 保存 MCP 初始化过程的结果。
		 * 它用于缓存结果以供后续请求使用。
		 */
		private final AtomicReference<McpSchema.InitializeResult> result;

		/**
		 * Holds the MCP client session that is used to communicate with the server. It is
		 * set during the initialization process and used for sending requests and
		 * notifications.
		 * 用于保存与服务器通信的 MCP 客户端会话。
		 * 它在初始化过程中设置，用于发送请求和通知。
		 */
		private final AtomicReference<McpClientSession> mcpClientSession;

		private DefaultInitialization() {
			this.initSink = Sinks.one();
			this.result = new AtomicReference<>();
			this.mcpClientSession = new AtomicReference<>();
		}

		// ---------------------------------------------------
		// Public access for mcpSession and initializeResult because they are
		// used in by the McpAsyncClient.
		// 因为 mcpSession 和 initializeResult 被 McPAsyncClient 使用，所以需要公开访问权限。
		// ----------------------------------------------------
		public McpClientSession mcpSession() {
			return this.mcpClientSession.get();
		}

		public McpSchema.InitializeResult initializeResult() {
			return this.result.get();
		}

		// ---------------------------------------------------
		// Private accessors used internally by the LifecycleInitializer to set the MCP
		// client session and complete the initialization process.
		// ---------------------------------------------------
		private void setMcpClientSession(McpClientSession mcpClientSession) {
			this.mcpClientSession.set(mcpClientSession);
		}

		private Mono<McpSchema.InitializeResult> await() {
			return this.initSink.asMono();
		}

		private void complete(McpSchema.InitializeResult initializeResult) {
			// inform all the subscribers waiting for the initialization
			this.initSink.emitValue(initializeResult, Sinks.EmitFailureHandler.FAIL_FAST);
		}

		private void cacheResult(McpSchema.InitializeResult initializeResult) {
			// first ensure the result is cached
			this.result.set(initializeResult);
		}

		private void error(Throwable t) {
			this.initSink.emitError(t, Sinks.EmitFailureHandler.FAIL_FAST);
		}

		private void close() {
			this.mcpSession().close();
		}

		private Mono<Void> closeGracefully() {
			return this.mcpSession().closeGracefully();
		}

	}

	public boolean isInitialized() {
		return this.currentInitializationResult() != null;
	}

	public McpSchema.InitializeResult currentInitializationResult() {
		DefaultInitialization current = this.initializationRef.get();
		McpSchema.InitializeResult initializeResult = current != null ? current.result.get() : null;
		return initializeResult;
	}

	/**
	 * Hook to handle exceptions that occur during the MCP transport session.
	 * <p>
	 * If the exception is a {@link McpTransportSessionNotFoundException}, it indicates
	 * that the session was not found, and we should re-initialize the client.
	 * </p>
	 * @param t The exception to handle
	 */
	public void handleException(Throwable t) {
		logger.warn("Handling exception", t);
		if (t instanceof McpTransportSessionNotFoundException) {
			DefaultInitialization previous = this.initializationRef.getAndSet(null);
			if (previous != null) {
				previous.close();
			}
			// Providing an empty operation since we are only interested in triggering
			// the implicit initialization step.
			this.withInitialization("re-initializing", result -> Mono.empty()).subscribe();
		}
	}

	/**
	 * Utility method to ensure the initialization is established before executing an
	 * operation.
	 * 用于确保在执行操作之前完成初始化的实用方法。
	 * @param <T> The type of the result Mono 结果类型为 Mono
	 * @param actionName The action to perform when the client is initialized
	 *                   客户端初始化时要执行的操作
	 * @param operation The operation to execute when the client is initialized
	 *                   客户端初始化时要执行的操作
	 * @return A Mono that completes with the result of the operation
	 */
	public <T> Mono<T> withInitialization(String actionName, Function<Initialization, Mono<T>> operation) {
		return Mono.deferContextual(ctx -> {
			DefaultInitialization newInit = new DefaultInitialization();
			DefaultInitialization previous = this.initializationRef.compareAndExchange(null, newInit);

			boolean needsToInitialize = previous == null;
			logger.debug(needsToInitialize ? "Initialization process started" : "Joining previous initialization");

			Mono<McpSchema.InitializeResult> initializationJob = needsToInitialize
					? this.doInitialize(newInit, this.postInitializationHook, ctx) : previous.await();

			return initializationJob.map(initializeResult -> this.initializationRef.get())
				.timeout(this.initializationTimeout)
				.onErrorResume(ex -> {
					this.initializationRef.compareAndSet(newInit, null);
					return Mono.error(new RuntimeException("Client failed to initialize " + actionName, ex));
				})
				.flatMap(res -> operation.apply(res)
					.contextWrite(c -> c.put(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION,
							res.initializeResult().protocolVersion())));
		});
	}

	private Mono<McpSchema.InitializeResult> doInitialize(DefaultInitialization initialization,
			Function<Initialization, Mono<Void>> postInitOperation, ContextView ctx) {

		initialization.setMcpClientSession(this.sessionSupplier.apply(ctx));

		McpClientSession mcpClientSession = initialization.mcpSession();

		String latestVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

		McpSchema.InitializeRequest initializeRequest = new McpSchema.InitializeRequest(latestVersion,
				this.clientCapabilities, this.clientInfo);

		Mono<McpSchema.InitializeResult> result = mcpClientSession.sendRequest(McpSchema.METHOD_INITIALIZE,
				initializeRequest, McpAsyncClient.INITIALIZE_RESULT_TYPE_REF);

		return result.flatMap(initializeResult -> {
			logger.info("Server response with Protocol: {}, Capabilities: {}, Info: {} and Instructions {}",
					initializeResult.protocolVersion(), initializeResult.capabilities(), initializeResult.serverInfo(),
					initializeResult.instructions());

			if (!this.protocolVersions.contains(initializeResult.protocolVersion())) {
				return Mono.error(McpError.builder(-32602)
					.message("Unsupported protocol version")
					.data("Unsupported protocol version from the server: " + initializeResult.protocolVersion())
					.build());
			}

			return mcpClientSession.sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED, null)
				.contextWrite(
						c -> c.put(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION, initializeResult.protocolVersion()))
				.thenReturn(initializeResult);
		}).flatMap(initializeResult -> {
			initialization.cacheResult(initializeResult);
			return postInitOperation.apply(initialization).thenReturn(initializeResult);
		}).doOnNext(initialization::complete).onErrorResume(ex -> {
			initialization.error(ex);
			return Mono.error(ex);
		});
	}

	/**
	 * Closes the current initialization if it exists.
	 */
	public void close() {
		DefaultInitialization current = this.initializationRef.getAndSet(null);
		if (current != null) {
			current.close();
		}
	}

	/**
	 * Gracefully closes the current initialization if it exists.
	 * @return A Mono that completes when the connection is closed
	 */
	public Mono<?> closeGracefully() {
		return Mono.defer(() -> {
			DefaultInitialization current = this.initializationRef.getAndSet(null);
			Mono<?> sessionClose = current != null ? current.closeGracefully() : Mono.empty();
			return sessionClose;
		});
	}

}