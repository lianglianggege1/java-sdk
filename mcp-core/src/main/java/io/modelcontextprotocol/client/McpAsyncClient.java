/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.modelcontextprotocol.client.LifecycleInitializer.Initialization;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientSession.NotificationHandler;
import io.modelcontextprotocol.spec.McpClientSession.RequestHandler;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.util.ToolNameValidator;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Model Context Protocol (MCP) client implementation that provides asynchronous
 * communication with MCP servers using Project Reactor's Mono and Flux types.
 * 模型上下文协议 (MCP) 客户端实现，使用 Project Reactor 的 Mono 和 Flux 类型与 MCP 服务器进行异步通信。
 *
 * <p>
 * This client implements the MCP specification, enabling AI models to interact with
 * external tools and resources through a standardized interface. Key features include:
 * 该客户端实现了MCP规范，使AI模型能够通过标准化接口与外部工具和资源进行交互。主要功能包括：
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns
 *     使用响应式编程模式的异步通信
 * <li>Tool discovery and invocation for server-provided functionality
 *     工具发现和调用，以实现服务器提供的功能
 * <li>Resource access and management with URI-based addressing
 *     资源获取与管理，使用URI地址进行访问
 * <li>Prompt template handling for standardized AI interactions
 *     针对标准化人工智能交互的提示模板处理
 * <li>Real-time notifications for tools, resources, and prompts changes
 *     工具、资源和提示变更的实时通知
 * <li>Structured logging with configurable severity levels
 *     具有可配置高级别的结构化日志记录
 * <li>Message sampling for AI model interactions
 *     用于人工智能模型交互的消息采样
 * </ul>
 *
 * <p>
 * The client follows a lifecycle:
 * 该客户端遵循生命周期：
 * <ol>
 * <li>Initialization - Establishes connection and negotiates capabilities
 *     初始化 - 建立连接并协商功能
 * <li>Normal Operation - Handles requests and notifications
 *     正常操作 - 处理请求和通知
 * <li>Graceful Shutdown - Ensures clean connection termination
 *     优雅关机 - 确保连接正常终止
 * </ol>
 *
 * <p>
 * This implementation uses Project Reactor for non-blocking operations, making it
 * suitable for high-throughput scenarios and reactive applications. All operations return
 * Mono or Flux types that can be composed into reactive pipelines.
 * 此实现使用 Project Reactor 进行非阻塞操作，因此适用于高吞吐量场景和响应式应用程序。
 * 所有操作都返回 Mono 或 Flux 类型，这些类型可以组合成响应式管道。
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Jihoon Kim
 * @author Anurag Pant
 * @see McpClient
 * @see McpSchema
 * @see McpClientSession
 * @see McpClientTransport
 */
public class McpAsyncClient {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncClient.class);

	private static final TypeRef<Void> VOID_TYPE_REFERENCE = new TypeRef<>() {
	};

	public static final TypeRef<Object> OBJECT_TYPE_REF = new TypeRef<>() {
	};

	public static final TypeRef<PaginatedRequest> PAGINATED_REQUEST_TYPE_REF = new TypeRef<>() {
	};

	public static final TypeRef<McpSchema.InitializeResult> INITIALIZE_RESULT_TYPE_REF = new TypeRef<>() {
	};

	public static final TypeRef<CreateMessageRequest> CREATE_MESSAGE_REQUEST_TYPE_REF = new TypeRef<>() {
	};

	public static final TypeRef<LoggingMessageNotification> LOGGING_MESSAGE_NOTIFICATION_TYPE_REF = new TypeRef<>() {
	};

	public static final TypeRef<McpSchema.ProgressNotification> PROGRESS_NOTIFICATION_TYPE_REF = new TypeRef<>() {
	};

	public static final String NEGOTIATED_PROTOCOL_VERSION = "io.modelcontextprotocol.client.negotiated-protocol-version";

	/**
	 * Client capabilities.
	 * 客户能力。
	 */
	private final McpSchema.ClientCapabilities clientCapabilities;

	/**
	 * Client implementation information.
	 * 客户实施信息。
	 */
	private final McpSchema.Implementation clientInfo;

	/**
	 * Roots define the boundaries of where servers can operate within the filesystem,
	 * allowing them to understand which directories and files they have access to.
	 * Servers can request the list of roots from supporting clients and receive
	 * notifications when that list changes.
	 * 根目录定义了服务器在文件系统中可以运行的边界，使服务器能够了解它们可以访问哪些目录和文件。
	 * 服务器可以向支持的客户端请求根目录列表，并在该列表发生更改时接收通知。
	 */
	private final ConcurrentHashMap<String, Root> roots;

	/**
	 * MCP provides a standardized way for servers to request LLM sampling ("completions"
	 * or "generations") from language models via clients. This flow allows clients to
	 * maintain control over model access, selection, and permissions while enabling
	 * servers to leverage AI capabilities—with no server API keys necessary. Servers can
	 * request text or image-based interactions and optionally include context from MCP
	 * servers in their prompts.
	 * MCP 为服务器提供了一种标准化的方式，使其能够通过客户端向语言模型请求 LLM 采样（“补全”或“生成”）。
	 * 这种流程允许客户端保持对模型访问、选择和权限的控制，同时使服务器能够利用 AI 功能——无需服务器 API 密钥。
	 * 服务器可以请求基于文本或图像的交互，并可选择在其提示中包含来自 MCP 服务器的上下文信息。
	 */
	private Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler;

	/**
	 * MCP provides a standardized way for servers to request additional information from
	 * users through the client during interactions. This flow allows clients to maintain
	 * control over user interactions and data sharing while enabling servers to gather
	 * necessary information dynamically. Servers can request structured data from users
	 * with optional JSON schemas to validate responses.
	 * MCP 提供了一种标准化的方式，使服务器能够在交互过程中通过客户端向用户请求额外信息。
	 * 这种流程允许客户端保持对用户交互和数据共享的控制，同时使服务器能够动态地收集必要信息。
	 * 服务器可以向用户请求结构化数据，并可选择使用 JSON 模式来验证响应。
	 */
	private Function<ElicitRequest, Mono<ElicitResult>> elicitationHandler;

	/**
	 * Client transport implementation.
	 * 客户端传输实现。
	 */
	private final McpClientTransport transport;

	/**
	 * The lifecycle initializer that manages the client-server connection initialization.
	 * 管理客户端-服务器连接初始化的生命周期初始化器。
	 */
	private final LifecycleInitializer initializer;

	/**
	 * JSON schema validator to use for validating tool responses against output schemas.
	 * 用于验证工具响应是否符合输出模式的 JSON 模式验证器。
	 */
	private final JsonSchemaValidator jsonSchemaValidator;

	/**
	 * Cached tool output schemas.
	 * 缓存的工具输出模式。
	 */
	private final ConcurrentHashMap<String, Map<String, Object>> toolsOutputSchemaCache;

	/**
	 * Whether to enable automatic schema caching during callTool operations.
	 * 是否在 callTool 操作期间启用自动模式缓存。
	 */
	private final boolean enableCallToolSchemaCaching;

	/**
	 * Create a new McpAsyncClient with the given transport and session request-response
	 * timeout.
	 * 使用给定的传输方式和会话请求-响应超时时间创建一个新的 McpAsyncClient。
	 * @param transport the transport to use.  使用的交通工具。
	 * @param requestTimeout the session request-response timeout. 会话请求-响应超时。
	 * @param initializationTimeout the max timeout to await for the client-server 等待客户端-服务器的最大超时时间
	 * @param jsonSchemaValidator the JSON schema validator to use for validating tool 用于验证工具的 JSON 模式验证器
	 * @param features the MCP Client supported features. responses against output MCP客户端支持的功能。针对输出的响应
	 * schemas.
	 */
	McpAsyncClient(McpClientTransport transport, Duration requestTimeout, Duration initializationTimeout,
			JsonSchemaValidator jsonSchemaValidator, McpClientFeatures.Async features) {

		Assert.notNull(transport, "Transport must not be null");
		Assert.notNull(requestTimeout, "Request timeout must not be null");
		Assert.notNull(initializationTimeout, "Initialization timeout must not be null");

		this.clientInfo = features.clientInfo();
		this.clientCapabilities = features.clientCapabilities();
		this.transport = transport;
		this.roots = new ConcurrentHashMap<>(features.roots());
		this.jsonSchemaValidator = jsonSchemaValidator;
		this.toolsOutputSchemaCache = new ConcurrentHashMap<>();
		this.enableCallToolSchemaCaching = features.enableCallToolSchemaCaching();

		// Request Handlers
		// 请求处理程序
		Map<String, RequestHandler<?>> requestHandlers = new HashMap<>();

		// Ping MUST respond with an empty data, but not NULL response.
		// Ping 必须返回空数据，但不能返回 NULL 响应。
		requestHandlers.put(McpSchema.METHOD_PING, params -> {
			logger.debug("Received ping: {}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
			return Mono.just(Map.of());
		});

		// Roots List Request Handler
		// 根列表请求处理程序
		if (this.clientCapabilities.roots() != null) {
			requestHandlers.put(McpSchema.METHOD_ROOTS_LIST, rootsListRequestHandler());
		}

		// Sampling Handler
		// 采样处理程序
		if (this.clientCapabilities.sampling() != null) {
			if (features.samplingHandler() == null) {
				throw new IllegalArgumentException(
						"Sampling handler must not be null when client capabilities include sampling");
			}
			this.samplingHandler = features.samplingHandler();
			requestHandlers.put(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, samplingCreateMessageHandler());
		}

		// Elicitation Handler
		// 引出处理程序
		if (this.clientCapabilities.elicitation() != null) {
			if (features.elicitationHandler() == null) {
				throw new IllegalArgumentException(
						"Elicitation handler must not be null when client capabilities include elicitation");
			}
			this.elicitationHandler = features.elicitationHandler();
			requestHandlers.put(McpSchema.METHOD_ELICITATION_CREATE, elicitationCreateHandler());
		}

		// Notification Handlers
		Map<String, NotificationHandler> notificationHandlers = new HashMap<>();

		// Tools Change Notification
		List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumersFinal = new ArrayList<>();
		toolsChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Tools changed: {}", notification)));

		if (!Utils.isEmpty(features.toolsChangeConsumers())) {
			toolsChangeConsumersFinal.addAll(features.toolsChangeConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
				asyncToolsChangeNotificationHandler(toolsChangeConsumersFinal));

		// Resources Change Notification
		List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumersFinal = new ArrayList<>();
		resourcesChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Resources changed: {}", notification)));

		if (!Utils.isEmpty(features.resourcesChangeConsumers())) {
			resourcesChangeConsumersFinal.addAll(features.resourcesChangeConsumers());
		}

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,
				asyncResourcesChangeNotificationHandler(resourcesChangeConsumersFinal));

		// Resources Update Notification
		List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumersFinal = new ArrayList<>();
		resourcesUpdateConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Resources updated: {}", notification)));

		if (!Utils.isEmpty(features.resourcesUpdateConsumers())) {
			resourcesUpdateConsumersFinal.addAll(features.resourcesUpdateConsumers());
		}

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
				asyncResourcesUpdatedNotificationHandler(resourcesUpdateConsumersFinal));

		// Prompts Change Notification
		List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumersFinal = new ArrayList<>();
		promptsChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Prompts changed: {}", notification)));
		if (!Utils.isEmpty(features.promptsChangeConsumers())) {
			promptsChangeConsumersFinal.addAll(features.promptsChangeConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED,
				asyncPromptsChangeNotificationHandler(promptsChangeConsumersFinal));

		// Utility Logging Notification
		List<Function<LoggingMessageNotification, Mono<Void>>> loggingConsumersFinal = new ArrayList<>();
		loggingConsumersFinal.add((notification) -> Mono.fromRunnable(() -> logger.debug("Logging: {}", notification)));
		if (!Utils.isEmpty(features.loggingConsumers())) {
			loggingConsumersFinal.addAll(features.loggingConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_MESSAGE,
				asyncLoggingNotificationHandler(loggingConsumersFinal));

		// Utility Progress Notification
		List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumersFinal = new ArrayList<>();
		progressConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Progress: {}", notification)));
		if (!Utils.isEmpty(features.progressConsumers())) {
			progressConsumersFinal.addAll(features.progressConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_PROGRESS,
				asyncProgressNotificationHandler(progressConsumersFinal));

		Function<Initialization, Mono<Void>> postInitializationHook = init -> {

			if (init.initializeResult().capabilities().tools() == null || !enableCallToolSchemaCaching) {
				return Mono.empty();
			}

			return this.listToolsInternal(init, McpSchema.FIRST_PAGE).doOnNext(listToolsResult -> {
				listToolsResult.tools()
					.forEach(tool -> logger.debug("Tool {} schema: {}", tool.name(), tool.outputSchema()));
				if (enableCallToolSchemaCaching && listToolsResult.tools() != null) {
					// Cache tools output schema
					listToolsResult.tools()
						.stream()
						.filter(tool -> tool.outputSchema() != null)
						.forEach(tool -> this.toolsOutputSchemaCache.put(tool.name(), tool.outputSchema()));
				}
			}).then();
		};

		this.initializer = new LifecycleInitializer(clientCapabilities, clientInfo, transport.protocolVersions(),
				initializationTimeout, ctx -> new McpClientSession(requestTimeout, transport, requestHandlers,
						notificationHandlers, con -> con.contextWrite(ctx)),
				postInitializationHook);

		this.transport.setExceptionHandler(this.initializer::handleException);
	}

	/**
	 * Get the current initialization result.
	 * @return the initialization result.
	 */
	public McpSchema.InitializeResult getCurrentInitializationResult() {
		return this.initializer.currentInitializationResult();
	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		McpSchema.InitializeResult initializeResult = this.initializer.currentInitializationResult();
		return initializeResult != null ? initializeResult.capabilities() : null;
	}

	/**
	 * Get the server instructions that provide guidance to the client on how to interact
	 * with this server.
	 * @return The server instructions
	 */
	public String getServerInstructions() {
		McpSchema.InitializeResult initializeResult = this.initializer.currentInitializationResult();
		return initializeResult != null ? initializeResult.instructions() : null;
	}

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	public McpSchema.Implementation getServerInfo() {
		McpSchema.InitializeResult initializeResult = this.initializer.currentInitializationResult();
		return initializeResult != null ? initializeResult.serverInfo() : null;
	}

	/**
	 * Check if the client-server connection is initialized.
	 * @return true if the client-server connection is initialized
	 */
	public boolean isInitialized() {
		return this.initializer.isInitialized();
	}

	/**
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	public ClientCapabilities getClientCapabilities() {
		return this.clientCapabilities;
	}

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.clientInfo;
	}

	/**
	 * Closes the client connection immediately.
	 */
	public void close() {
		this.initializer.close();
		this.transport.close();
	}

	/**
	 * Gracefully closes the client connection.
	 * @return A Mono that completes when the connection is closed
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			return this.initializer.closeGracefully().then(transport.closeGracefully());
		});
	}

	// --------------------------
	// Initialization
	// --------------------------

	/**
	 * The initialization phase should be the first interaction between client and server.
	 * The client will ensure it happens in case it has not been explicitly called and in
	 * case of transport session invalidation.
	 * 初始化阶段应该是客户端和服务器之间的首次交互。如果客户端没有显式调用初始化阶段，或者传输会话失效，客户端将确保初始化阶段的执行。
	 * <p>
	 * During this phase, the client and server:
	 * 在此阶段，客户端和服务器：
	 * <ul>
	 * <li>Establish protocol version compatibility</li>
	 *     建立协议版本兼容性
	 * <li>Exchange and negotiate capabilities</li>
	 *     交流和协商能力
	 * <li>Share implementation details</li>
	 *     分享实施细节
	 * </ul>
	 * <br/>
	 * The client MUST initiate this phase by sending an initialize request containing:
	 * The protocol version the client supports, client's capabilities and clients
	 * implementation information.
	 * 客户端必须通过发送包含以下内容的初始化请求来启动此阶段：客户端支持的协议版本、客户端的功能和客户端的实现信息。
	 * <p>
	 * The server MUST respond with its own capabilities and information.
	 * 服务器必须提供自身的功能和信息进行响应。
	 * </p>
	 * After successful initialization, the client MUST send an initialized notification
	 * to indicate it is ready to begin normal operations.
	 * 初始化成功后，客户端必须发送初始化通知，表明它已准备好开始正常操作。
	 * @return the initialize result.
	 * @see <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">MCP
	 * Initialization Spec</a>
	 * </p>
	 */
	public Mono<McpSchema.InitializeResult> initialize() {
		return this.initializer.withInitialization("by explicit API call", init -> Mono.just(init.initializeResult()));
	}

	// --------------------------
	// Basic Utilities
	// --------------------------

	/**
	 * Sends a ping request to the server.
	 * @return A Mono that completes with the server's ping response
	 */
	public Mono<Object> ping() {
		return this.initializer.withInitialization("pinging the server",
				init -> init.mcpSession().sendRequest(McpSchema.METHOD_PING, null, OBJECT_TYPE_REF));
	}

	// --------------------------
	// Roots
	// --------------------------

	/**
	 * Adds a new root to the client's root list.
	 * @param root The root to add.
	 * @return A Mono that completes when the root is added and notifications are sent.
	 */
	public Mono<Void> addRoot(Root root) {

		if (root == null) {
			return Mono.error(new IllegalArgumentException("Root must not be null"));
		}

		if (this.clientCapabilities.roots() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with roots capabilities"));
		}

		if (this.roots.containsKey(root.uri())) {
			return Mono.error(new IllegalStateException("Root with uri '" + root.uri() + "' already exists"));
		}

		this.roots.put(root.uri(), root);

		logger.debug("Added root: {}", root);

		if (this.clientCapabilities.roots().listChanged()) {
			if (this.isInitialized()) {
				return this.rootsListChangedNotification();
			}
			else {
				logger.warn("Client is not initialized, ignore sending a roots list changed notification");
			}
		}
		return Mono.empty();
	}

	/**
	 * Removes a root from the client's root list.
	 * @param rootUri The URI of the root to remove.
	 * @return A Mono that completes when the root is removed and notifications are sent.
	 */
	public Mono<Void> removeRoot(String rootUri) {

		if (rootUri == null) {
			return Mono.error(new IllegalArgumentException("Root uri must not be null"));
		}

		if (this.clientCapabilities.roots() == null) {
			return Mono.error(new IllegalStateException("Client must be configured with roots capabilities"));
		}

		Root removed = this.roots.remove(rootUri);

		if (removed != null) {
			logger.debug("Removed Root: {}", rootUri);
			if (this.clientCapabilities.roots().listChanged()) {
				if (this.isInitialized()) {
					return this.rootsListChangedNotification();
				}
				else {
					logger.warn("Client is not initialized, ignore sending a roots list changed notification");
				}

			}
			return Mono.empty();
		}
		return Mono.error(new IllegalStateException("Root with uri '" + rootUri + "' not found"));
	}

	/**
	 * Manually sends a roots/list_changed notification. The addRoot and removeRoot
	 * methods automatically send the roots/list_changed notification if the client is in
	 * an initialized state.
	 * 手动发送 roots/list_changed 通知。
	 * 如果客户端处于初始化状态，addRoot 和 removeRoot 方法会自动发送 roots/list_changed 通知。
	 * @return A Mono that completes when the notification is sent.
	 */
	public Mono<Void> rootsListChangedNotification() {
		return this.initializer.withInitialization("sending roots list changed notification",
				init -> init.mcpSession().sendNotification(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED));
	}

	private RequestHandler<McpSchema.ListRootsResult> rootsListRequestHandler() {
		return params -> {
			@SuppressWarnings("unused")
			McpSchema.PaginatedRequest request = transport.unmarshalFrom(params, PAGINATED_REQUEST_TYPE_REF);

			List<Root> roots = this.roots.values().stream().toList();

			return Mono.just(new McpSchema.ListRootsResult(roots));
		};
	}

	// --------------------------
	// Sampling
	// --------------------------
	private RequestHandler<CreateMessageResult> samplingCreateMessageHandler() {
		return params -> {
			McpSchema.CreateMessageRequest request = transport.unmarshalFrom(params, CREATE_MESSAGE_REQUEST_TYPE_REF);

			return this.samplingHandler.apply(request);
		};
	}

	// --------------------------
	// Elicitation
	// --------------------------
	private RequestHandler<ElicitResult> elicitationCreateHandler() {
		return params -> {
			ElicitRequest request = transport.unmarshalFrom(params, new TypeRef<>() {
			});

			return this.elicitationHandler.apply(request);
		};
	}

	// --------------------------
	// Tools
	// --------------------------
	private static final TypeRef<McpSchema.CallToolResult> CALL_TOOL_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.ListToolsResult> LIST_TOOLS_RESULT_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Calls a tool provided by the server. Tools enable servers to expose executable
	 * functionality that can interact with external systems, perform computations, and
	 * take actions in the real world.
	 * 调用服务器提供的工具。工具使服务器能够公开可执行功能，这些功能可以与外部系统交互、执行计算并在现实世界中采取行动。
	 * @param callToolRequest The request containing the tool name and input parameters.
	 * @return A Mono that emits the result of the tool call, including the output and any
	 * errors.
	 * @see McpSchema.CallToolRequest
	 * @see McpSchema.CallToolResult
	 * @see #listTools()
	 */
	public Mono<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest callToolRequest) {
		return this.initializer.withInitialization("calling tool", init -> {
			if (init.initializeResult().capabilities().tools() == null) {
				return Mono.error(new IllegalStateException("Server does not provide tools capability"));
			}

			return init.mcpSession()
				.sendRequest(McpSchema.METHOD_TOOLS_CALL, callToolRequest, CALL_TOOL_RESULT_TYPE_REF)
				.flatMap(result -> Mono.just(validateToolResult(callToolRequest.name(), result)));
		});
	}

	private McpSchema.CallToolResult validateToolResult(String toolName, McpSchema.CallToolResult result) {

		if (!this.enableCallToolSchemaCaching || result == null || result.isError() == Boolean.TRUE) {
			// if tool schema caching is disabled or tool call resulted in an error - skip
			// validation and return the result as it is
			return result;
		}

		Map<String, Object> optOutputSchema = this.toolsOutputSchemaCache.get(toolName);

		if (optOutputSchema == null) {
			logger.warn(
					"Calling a tool with no outputSchema is not expected to return result with structured content, but got: {}",
					result.structuredContent());
			return result;
		}

		// Validate the tool output against the cached output schema
		var validation = this.jsonSchemaValidator.validate(optOutputSchema, result.structuredContent());

		if (!validation.valid()) {
			logger.warn("Tool call result validation failed: {}", validation.errorMessage());
			throw new IllegalArgumentException("Tool call result validation failed: " + validation.errorMessage());
		}

		return result;
	}

	/**
	 * Retrieves the list of all tools provided by the server.
	 * 获取服务器提供的所有工具列表。
	 * @return A Mono that emits the list of all tools result
	 */
	public Mono<McpSchema.ListToolsResult> listTools() {
		return this.listTools(McpSchema.FIRST_PAGE).expand(result -> {
			String next = result.nextCursor();
			return (next != null && !next.isEmpty()) ? this.listTools(next) : Mono.empty();
		}).reduce(new McpSchema.ListToolsResult(new ArrayList<>(), null), (allToolsResult, result) -> {
			allToolsResult.tools().addAll(result.tools());
			return allToolsResult;
		}).map(result -> new McpSchema.ListToolsResult(Collections.unmodifiableList(result.tools()), null));
	}

	/**
	 * Retrieves a paginated list of tools provided by the server.
	 * 获取服务器提供的工具的分页列表。
	 * @param cursor Optional pagination cursor from a previous list request 来自先前列表请求的可选分页光标
	 * @return A Mono that emits the list of tools result 一个 Mono，它会输出工具列表结果。
	 */
	public Mono<McpSchema.ListToolsResult> listTools(String cursor) {
		return this.initializer.withInitialization("listing tools", init -> this.listToolsInternal(init, cursor));
	}

	private Mono<McpSchema.ListToolsResult> listToolsInternal(Initialization init, String cursor) {

		if (init.initializeResult().capabilities().tools() == null) {
			return Mono.error(new IllegalStateException("Server does not provide tools capability"));
		}
		return init.mcpSession()
			.sendRequest(McpSchema.METHOD_TOOLS_LIST, new McpSchema.PaginatedRequest(cursor),
					LIST_TOOLS_RESULT_TYPE_REF)
			.doOnNext(result -> {
				// Validate tool names (warn only)
				if (result.tools() != null) {
					result.tools().forEach(tool -> ToolNameValidator.validate(tool.name(), false));
				}
				if (this.enableCallToolSchemaCaching && result.tools() != null) {
					// Cache tools output schema
					result.tools()
						.stream()
						.filter(tool -> tool.outputSchema() != null)
						.forEach(tool -> this.toolsOutputSchemaCache.put(tool.name(), tool.outputSchema()));
				}
			});
	}

	private NotificationHandler asyncToolsChangeNotificationHandler(
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers) {
		// TODO: params are not used yet
		return params -> this.listTools()
			.flatMap(listToolsResult -> Flux.fromIterable(toolsChangeConsumers)
				.flatMap(consumer -> consumer.apply(listToolsResult.tools()))
				.onErrorResume(error -> {
					logger.error("Error handling tools list change notification", error);
					return Mono.empty();
				})
				.then());
	}

	// --------------------------
	// Resources
	// --------------------------

	private static final TypeRef<McpSchema.ListResourcesResult> LIST_RESOURCES_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.ReadResourceResult> READ_RESOURCE_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.ListResourceTemplatesResult> LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Retrieves the list of all resources provided by the server. Resources represent any
	 * kind of UTF-8 encoded data that an MCP server makes available to clients, such as
	 * database records, API responses, log files, and more.
	 * 检索服务器提供的所有资源列表。资源是指 MCP 服务器向客户端提供的任何类型的 UTF-8 编码数据，例如数据库记录、API 响应、日志文件等。
	 * @return A Mono that completes with the list of all resources result
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	public Mono<McpSchema.ListResourcesResult> listResources() {
		return this.listResources(McpSchema.FIRST_PAGE)
			.expand(result -> (result.nextCursor() != null) ? this.listResources(result.nextCursor()) : Mono.empty())
			.reduce(new McpSchema.ListResourcesResult(new ArrayList<>(), null), (allResourcesResult, result) -> {
				allResourcesResult.resources().addAll(result.resources());
				return allResourcesResult;
			})
			.map(result -> new McpSchema.ListResourcesResult(Collections.unmodifiableList(result.resources()), null));
	}

	/**
	 * Retrieves a paginated list of resources provided by the server. Resources represent
	 * any kind of UTF-8 encoded data that an MCP server makes available to clients, such
	 * as database records, API responses, log files, and more.
	 * 检索服务器提供的资源的分页列表。
	 * 资源代表 MCP 服务器向客户端提供的任何类型的 UTF-8 编码数据，
	 * 例如数据库记录、API 响应、日志文件等。
	 * @param cursor Optional pagination cursor from a previous list request.
	 *               来自先前列表请求的可选分页光标。
	 * @return A Mono that completes with the list of resources result.
	 *         一个返回资源列表结果的 Mono。
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	public Mono<McpSchema.ListResourcesResult> listResources(String cursor) {
		return this.initializer.withInitialization("listing resources", init -> {
			if (init.initializeResult().capabilities().resources() == null) {
				return Mono.error(new IllegalStateException("Server does not provide the resources capability"));
			}
			return init.mcpSession()
				.sendRequest(McpSchema.METHOD_RESOURCES_LIST, new McpSchema.PaginatedRequest(cursor),
						LIST_RESOURCES_RESULT_TYPE_REF);
		});
	}

	/**
	 * Reads the content of a specific resource identified by the provided Resource
	 * object. This method fetches the actual data that the resource represents.
	 * @param resource The resource to read, containing the URI that identifies the
	 * resource.
	 * 读取由提供的 Resource 对象标识的特定资源的内容。此方法获取资源所表示的实际数据。
	 * @return A Mono that completes with the resource content.
	 * @see McpSchema.Resource
	 * @see McpSchema.ReadResourceResult
	 */
	public Mono<McpSchema.ReadResourceResult> readResource(McpSchema.Resource resource) {
		return this.readResource(new McpSchema.ReadResourceRequest(resource.uri()));
	}

	/**
	 * Reads the content of a specific resource identified by the provided request. This
	 * method fetches the actual data that the resource represents.
	 * 读取请求中指定的资源的内容。此方法会获取资源所代表的实际数据。
	 * @param readResourceRequest The request containing the URI of the resource to read
	 *                            包含要读取的资源 URI 的请求
	 * @return A Mono that completes with the resource content.
	 *         一个与资源内容相匹配的 Mono。
	 * @see McpSchema.ReadResourceRequest
	 * @see McpSchema.ReadResourceResult
	 */
	public Mono<McpSchema.ReadResourceResult> readResource(McpSchema.ReadResourceRequest readResourceRequest) {
		return this.initializer.withInitialization("reading resources", init -> {
			if (init.initializeResult().capabilities().resources() == null) {
				return Mono.error(new IllegalStateException("Server does not provide the resources capability"));
			}
			return init.mcpSession()
				.sendRequest(McpSchema.METHOD_RESOURCES_READ, readResourceRequest, READ_RESOURCE_RESULT_TYPE_REF);
		});
	}

	/**
	 * Retrieves the list of all resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 * 获取服务器提供的所有资源模板列表。
	 * 资源模板允许服务器使用 URI 模板公开参数化资源，从而实现基于可变参数的动态资源访问。
	 * @return A Mono that completes with the list of all resource templates result
	 *         一个 Mono，其中包含所有资源模板的列表结果
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates() {
		return this.listResourceTemplates(McpSchema.FIRST_PAGE)
			.expand(result -> (result.nextCursor() != null) ? this.listResourceTemplates(result.nextCursor())
					: Mono.empty())
			.reduce(new McpSchema.ListResourceTemplatesResult(new ArrayList<>(), null),
					(allResourceTemplatesResult, result) -> {
						allResourceTemplatesResult.resourceTemplates().addAll(result.resourceTemplates());
						return allResourceTemplatesResult;
					})
			.map(result -> new McpSchema.ListResourceTemplatesResult(
					Collections.unmodifiableList(result.resourceTemplates()), null));
	}

	/**
	 * Retrieves a paginated list of resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 * 获取服务器提供的资源模板分页列表。
	 * 资源模板允许服务器使用 URI 模板公开参数化资源，从而实现基于可变参数的动态资源访问。
	 * @param cursor Optional pagination cursor from a previous list request.
	 *               来自先前列表请求的可选分页光标。
	 * @return A Mono that completes with the list of resource templates result.
	 *         一个 Mono，其结果为资源模板列表。
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(String cursor) {
		return this.initializer.withInitialization("listing resource templates", init -> {
			if (init.initializeResult().capabilities().resources() == null) {
				return Mono.error(new IllegalStateException("Server does not provide the resources capability"));
			}
			return init.mcpSession()
				.sendRequest(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, new McpSchema.PaginatedRequest(cursor),
						LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF);
		});
	}

	/**
	 * Subscribes to changes in a specific resource. When the resource changes on the
	 * server, the client will receive notifications through the resources change
	 * notification handler.
	 * 订阅特定资源的变更。当服务器上的资源发生变更时，客户端将通过资源变更通知处理程序收到通知。
	 * @param subscribeRequest The subscribe request containing the URI of the resource.
	 * @return A Mono that completes when the subscription is complete.
	 * @see McpSchema.SubscribeRequest
	 * @see #unsubscribeResource(McpSchema.UnsubscribeRequest)
	 */
	public Mono<Void> subscribeResource(McpSchema.SubscribeRequest subscribeRequest) {
		return this.initializer.withInitialization("subscribing to resources", init -> init.mcpSession()
			.sendRequest(McpSchema.METHOD_RESOURCES_SUBSCRIBE, subscribeRequest, VOID_TYPE_REFERENCE));
	}

	/**
	 * Cancels an existing subscription to a resource. After unsubscribing, the client
	 * will no longer receive notifications when the resource changes.
	 * 取消对某个资源的现有订阅。取消订阅后，客户端将不再收到该资源变更的通知。
	 * @param unsubscribeRequest The unsubscribe request containing the URI of the
	 * resource.
	 * @return A Mono that completes when the unsubscription is complete.
	 * @see McpSchema.UnsubscribeRequest
	 * @see #subscribeResource(McpSchema.SubscribeRequest)
	 */
	public Mono<Void> unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest) {
		return this.initializer.withInitialization("unsubscribing from resources", init -> init.mcpSession()
			.sendRequest(McpSchema.METHOD_RESOURCES_UNSUBSCRIBE, unsubscribeRequest, VOID_TYPE_REFERENCE));
	}

	private NotificationHandler asyncResourcesChangeNotificationHandler(
			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers) {
		return params -> listResources().flatMap(listResourcesResult -> Flux.fromIterable(resourcesChangeConsumers)
			.flatMap(consumer -> consumer.apply(listResourcesResult.resources()))
			.onErrorResume(error -> {
				logger.error("Error handling resources list change notification", error);
				return Mono.empty();
			})
			.then());
	}

	private NotificationHandler asyncResourcesUpdatedNotificationHandler(
			List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers) {
		return params -> {
			McpSchema.ResourcesUpdatedNotification resourcesUpdatedNotification = transport.unmarshalFrom(params,
					new TypeRef<>() {
					});

			return readResource(new McpSchema.ReadResourceRequest(resourcesUpdatedNotification.uri()))
				.flatMap(readResourceResult -> Flux.fromIterable(resourcesUpdateConsumers)
					.flatMap(consumer -> consumer.apply(readResourceResult.contents()))
					.onErrorResume(error -> {
						logger.error("Error handling resource update notification", error);
						return Mono.empty();
					})
					.then());
		};
	}

	// --------------------------
	// Prompts
	// --------------------------
	private static final TypeRef<McpSchema.ListPromptsResult> LIST_PROMPTS_RESULT_TYPE_REF = new TypeRef<>() {
	};

	private static final TypeRef<McpSchema.GetPromptResult> GET_PROMPT_RESULT_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Retrieves the list of all prompts provided by the server.
	 * 获取服务器提供的所有提示列表。
	 * @return A Mono that completes with the list of all prompts result.
	 *         一个 Mono，完成时会列出所有提示结果。
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(GetPromptRequest)
	 */
	public Mono<ListPromptsResult> listPrompts() {
		return this.listPrompts(McpSchema.FIRST_PAGE)
			.expand(result -> (result.nextCursor() != null) ? this.listPrompts(result.nextCursor()) : Mono.empty())
			.reduce(new ListPromptsResult(new ArrayList<>(), null), (allPromptsResult, result) -> {
				allPromptsResult.prompts().addAll(result.prompts());
				return allPromptsResult;
			})
			.map(result -> new McpSchema.ListPromptsResult(Collections.unmodifiableList(result.prompts()), null));
	}

	/**
	 * Retrieves a paginated list of prompts provided by the server.
	 * 获取服务器提供的分页提示列表。
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that completes with the list of prompts result.
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(GetPromptRequest)
	 */
	public Mono<ListPromptsResult> listPrompts(String cursor) {
		return this.initializer.withInitialization("listing prompts", init -> init.mcpSession()
			.sendRequest(McpSchema.METHOD_PROMPT_LIST, new PaginatedRequest(cursor), LIST_PROMPTS_RESULT_TYPE_REF));
	}

	/**
	 * Retrieves a specific prompt by its ID. This provides the complete prompt template
	 * including all parameters and instructions for generating AI content.
	 * 通过 ID 获取特定提示。这将提供完整的提示模板，包括生成 AI 内容所需的所有参数和指令。
	 * @param getPromptRequest The request containing the ID of the prompt to retrieve.
	 * @return A Mono that completes with the prompt result.
	 * @see McpSchema.GetPromptRequest
	 * @see McpSchema.GetPromptResult
	 * @see #listPrompts()
	 */
	public Mono<GetPromptResult> getPrompt(GetPromptRequest getPromptRequest) {
		return this.initializer.withInitialization("getting prompts", init -> init.mcpSession()
			.sendRequest(McpSchema.METHOD_PROMPT_GET, getPromptRequest, GET_PROMPT_RESULT_TYPE_REF));
	}

	private NotificationHandler asyncPromptsChangeNotificationHandler(
			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers) {
		return params -> listPrompts().flatMap(listPromptsResult -> Flux.fromIterable(promptsChangeConsumers)
			.flatMap(consumer -> consumer.apply(listPromptsResult.prompts()))
			.onErrorResume(error -> {
				logger.error("Error handling prompts list change notification", error);
				return Mono.empty();
			})
			.then());
	}

	// --------------------------
	// Logging
	// --------------------------
	private NotificationHandler asyncLoggingNotificationHandler(
			List<Function<LoggingMessageNotification, Mono<Void>>> loggingConsumers) {

		return params -> {
			McpSchema.LoggingMessageNotification loggingMessageNotification = transport.unmarshalFrom(params,
					LOGGING_MESSAGE_NOTIFICATION_TYPE_REF);

			return Flux.fromIterable(loggingConsumers)
				.flatMap(consumer -> consumer.apply(loggingMessageNotification))
				.then();
		};
	}

	/**
	 * Sets the minimum logging level for messages received from the server. The client
	 * will only receive log messages at or above the specified severity level.
	 * 设置从服务器接收消息的最低日志级别。客户端只会收到级别等于或高于指定级别的日志消息。
	 * @param loggingLevel The minimum logging level to receive.
	 * @return A Mono that completes when the logging level is set.
	 * @see McpSchema.LoggingLevel
	 */
	public Mono<Void> setLoggingLevel(LoggingLevel loggingLevel) {
		if (loggingLevel == null) {
			return Mono.error(new IllegalArgumentException("Logging level must not be null"));
		}

		return this.initializer.withInitialization("setting logging level", init -> {
			if (init.initializeResult().capabilities().logging() == null) {
				return Mono.error(new IllegalStateException("Server's Logging capabilities are not enabled!"));
			}
			var params = new McpSchema.SetLevelRequest(loggingLevel);
			return init.mcpSession().sendRequest(McpSchema.METHOD_LOGGING_SET_LEVEL, params, OBJECT_TYPE_REF).then();
		});
	}

	private NotificationHandler asyncProgressNotificationHandler(
			List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers) {

		return params -> {
			McpSchema.ProgressNotification progressNotification = transport.unmarshalFrom(params,
					PROGRESS_NOTIFICATION_TYPE_REF);

			return Flux.fromIterable(progressConsumers)
				.flatMap(consumer -> consumer.apply(progressNotification))
				.then();
		};
	}

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * 此方法是包私有的，仅用于测试。用户代码不应调用此方法。
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.initializer.setProtocolVersions(protocolVersions);
	}

	// --------------------------
	// Completions
	// --------------------------
	private static final TypeRef<McpSchema.CompleteResult> COMPLETION_COMPLETE_RESULT_TYPE_REF = new TypeRef<>() {
	};

	/**
	 * Sends a completion/complete request to generate value suggestions based on a given
	 * reference and argument. This is typically used to provide auto-completion options
	 * for user input fields.
	 * 发送完成/补全请求，根据给定的引用和参数生成值建议。这通常用于为用户输入字段提供自动补全选项。
	 * @param completeRequest The request containing the prompt or resource reference and
	 * argument for which to generate completions.
	 * @return A Mono that completes with the result containing completion suggestions.
	 * @see McpSchema.CompleteRequest
	 * @see McpSchema.CompleteResult
	 */
	public Mono<McpSchema.CompleteResult> completeCompletion(McpSchema.CompleteRequest completeRequest) {
		return this.initializer.withInitialization("complete completions", init -> init.mcpSession()
			.sendRequest(McpSchema.METHOD_COMPLETION_COMPLETE, completeRequest, COMPLETION_COMPLETE_RESULT_TYPE_REF));
	}

}
