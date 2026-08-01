package com.toptrumps.session

import kotlinx.serialization.json.Json

/** `WireMessage <-> ByteArray`. The one thing every byte crossing a [Transport] passes through. */
public object ProtocolCodec {
    private val json = Json { ignoreUnknownKeys = true }

    public fun encodeGuestToHost(message: GuestToHost): ByteArray =
        json.encodeToString(GuestToHost.serializer(), message).encodeToByteArray()

    public fun decodeGuestToHost(bytes: ByteArray): GuestToHost =
        json.decodeFromString(GuestToHost.serializer(), bytes.decodeToString())

    public fun encodeHostToGuest(message: HostToGuest): ByteArray =
        json.encodeToString(HostToGuest.serializer(), message).encodeToByteArray()

    public fun decodeHostToGuest(bytes: ByteArray): HostToGuest =
        json.decodeFromString(HostToGuest.serializer(), bytes.decodeToString())

    public fun encodeLobbyMessage(message: LobbyMessage): ByteArray =
        json.encodeToString(LobbyMessage.serializer(), message).encodeToByteArray()

    public fun decodeLobbyMessage(bytes: ByteArray): LobbyMessage =
        json.decodeFromString(LobbyMessage.serializer(), bytes.decodeToString())
}
