package dev.highlights.core

import dev.highlights.core.session.SessionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes

class SessionStoreTest : FunSpec({
    test("une vidéo passée à la place de sa session : message clair, sans la lire") {
        val video = createTempDirectory("session").resolve("Valorant_10-01-2026_22-17-23-0.mp4")
        video.writeBytes(ByteArray(64))
        shouldThrow<InputException> { SessionStore.load(video) }.message shouldContain "n'est pas une session"
    }
})
