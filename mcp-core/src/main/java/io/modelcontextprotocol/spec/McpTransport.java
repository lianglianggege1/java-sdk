/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;

/**
 * Defines the asynchronous transport layer for the Model Context Protocol (MCP).
 * 定义模型上下文协议 (MCP) 的异步传输层。
 *
 * <p>
 * The McpTransport interface provides the foundation for implementing custom transport
 * mechanisms in the Model Context Protocol. It handles the bidirectional communication
 * between the client and server components, supporting asynchronous message exchange
 * using JSON-RPC format.
 * McpTransport 接口为在模型上下文协议中实现自定义传输机制提供了基础。
 * 它处理客户端和服务器组件之间的双向通信，支持使用 JSON-RPC 格式的异步消息交换。
 * </p>
 *
 * <p>
 * Implementations of this interface are responsible for:
 * 该接口的实现负责：
 * </p>
 * <ul>
 * <li>Managing the lifecycle of the transport connection</li>
 *     管理运输连接的生命周期
 * <li>Handling incoming messages and errors from the server</li>
 *     处理来自服务器的传入消息和错误
 * <li>Sending outbound messages to the server</li>
 *     向服务器发送出站消息
 * </ul>
 *
 * <p>
 * The transport layer is designed to be protocol-agnostic, allowing for various
 * implementations such as WebSocket, HTTP, or custom protocols.
 * 传输层设计为与协议无关，允许使用各种实现方式，例如 WebSocket、HTTP 或自定义协议。
 * </p>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpTransport {

	/**
	 * Closes the transport connection and releases any associated resources.
	 * 关闭传输连接并释放所有相关资源。
	 *
	 * <p>
	 * This method ensures proper cleanup of resources when the transport is no longer
	 * needed. It should handle the graceful shutdown of any active connections.
	 * 此方法可确保在不再需要传输时正确清理资源，并妥善关闭所有活动连接。
	 * </p>
	 */
	default void close() {
		this.closeGracefully().subscribe();
	}

	/**
	 * Closes the transport connection and releases any associated resources
	 * asynchronously.
	 * 关闭传输连接并异步释放所有相关资源。
	 * @return a {@link Mono<Void>} that completes when the connection has been closed.
	 */
	Mono<Void> closeGracefully();

	/**
	 * Sends a message to the peer asynchronously.
	 * 异步地向对端发送消息。
	 *
	 * <p>
	 * This method handles the transmission of messages to the server in an asynchronous
	 * manner. Messages are sent in JSON-RPC format as specified by the MCP protocol.
	 * 此方法以异步方式处理向服务器发送消息。消息以 MCP 协议规定的 JSON-RPC 格式发送。
	 * </p>
	 * @param message the {@link JSONRPCMessage} to be sent to the server
	 * @return a {@link Mono<Void>} that completes when the message has been sent
	 */
	Mono<Void> sendMessage(JSONRPCMessage message);

	/**
	 * Unmarshals the given data into an object of the specified type.
	 * 将给定的数据反序列化为指定类型的对象。
	 * @param <T> the type of the object to unmarshal
	 * @param data the data to unmarshal
	 * @param typeRef the type reference for the object to unmarshal
	 * @return the unmarshalled object
	 */
	<T> T unmarshalFrom(Object data, TypeRef<T> typeRef);

	default List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2024_11_05);
	}

}
