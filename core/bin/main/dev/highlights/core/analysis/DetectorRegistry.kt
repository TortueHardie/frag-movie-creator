package dev.highlights.core.analysis

import dev.highlights.core.ConfigException
import java.util.ServiceLoader

class DetectorRegistry(factories: Iterable<SignalDetectorFactory>) {
    private val byType: Map<String, SignalDetectorFactory> = factories.associateBy { it.type }

    val types: Set<String> get() = byType.keys

    fun create(type: String, id: String, params: DetectorParams): SignalDetector {
        val factory = byType[type] ?: throw ConfigException(
            "Détecteur inconnu '$type' (instance '$id'). Disponibles : ${types.sorted().joinToString().ifEmpty { "aucun" }}",
        )
        return factory.create(id, params)
    }

    companion object {
        fun fromServiceLoader(classLoader: ClassLoader = DetectorRegistry::class.java.classLoader) =
            DetectorRegistry(ServiceLoader.load(SignalDetectorFactory::class.java, classLoader))
    }
}
