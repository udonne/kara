package kotlinx.reflection

import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.jvm.internal.FunctionReference
import kotlin.jvm.internal.Reflection
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

private object NullMask
private fun Any.unmask():Any? = if (this == NullMask) null else this

// can be removed when fixed: KT-17594 consider caching for Class<T>.kotlin
@Suppress("USELESS_CAST")
val <T: Any> Class<T>.kotlinCached: KClass<T>
    get() = Reflection.getOrCreateKotlinClass(this) as KClass<T>

@Suppress("UNCHECKED_CAST")
val <T : Annotation> T.annotationClassCached: KClass<out T>
    get() = (this as java.lang.annotation.Annotation).annotationType().kotlinCached as KClass<out T>

private val propertyGetters = ConcurrentHashMap<Pair<KClass<out Any>, String>, Any>()

fun <T:Any, R:Any?> T.propertyValue(property: String): R? {
    @Suppress("UNCHECKED_CAST")
    val getter = propertyGetters.getOrPut(Pair(this::class, property),  {
        this::class.memberProperties.firstOrNull { it.name == property } ?: NullMask
    }).unmask() as KProperty1<T, R>? ?: error("Invalid property $property on type ${this::class.simpleName}")
    return getter.get(this)
}

class MissingArgumentException(message: String) : RuntimeException(message)

fun <T:Any> KClass<T>.buildBeanInstance(allParams: Map<String, String>): T =
        objectInstance ?: primaryConstructor!!.resolveAndCall(allParams, java.classLoader)

fun KCallable<*>.boundReceiver() = (this as? FunctionReference)?.boundReceiver ?:
        (parameters.find { it.kind == KParameter.Kind.INSTANCE && it.index == 0 }?.type?.classifier as? KClass<*>)?.objectInstance

fun <R:Any> KCallable<R>.resolveAndCall(allParams: Map<String, String>, classLoader: ClassLoader? = javaClass.classLoader) : R {
    val args = parameters.map { param ->
        val stringValue = allParams[param.name]
        val kclazz = paramJavaType(param.type.javaType).kotlinCached
        val isNullable = param.type.isMarkedNullable
        param to when {
            param.kind == KParameter.Kind.INSTANCE -> boundReceiver()!!
            stringValue == "null" && isNullable -> null
            stringValue == "" && kclazz != String::class && isNullable -> null
            stringValue != null -> Serialization.deserialize(stringValue, kclazz, classLoader)  ?: throw MissingArgumentException("Bad argument ${param.name}='$stringValue'")
            param.isOptional -> NullMask
            isNullable -> null
            else -> throw MissingArgumentException("Required argument '${param.name}' is missing, available params: $allParams")
        }
    }.filter { it.second != NullMask }.toMap()

    return callBy(args)
}

@Suppress("UNCHECKED_CAST")
private fun paramJavaType(javaType: Type): Class<Any> = when (javaType) {
    is ParameterizedType -> paramJavaType(javaType.rawType)
    is Class<*> -> javaType as Class<Any>
    else -> error("Unsupported type")
}

fun Class<*>.isEnumClass(): Boolean = Enum::class.java.isAssignableFrom(this)

fun ClassLoader.findClasses(prefix: String, cache: MutableMap<Pair<Int, String>, List<Class<*>>>) : List<Class<*>> {
    return synchronized(cache) {
        cache.getOrPut(this.hashCode() to prefix) {
            scanForClasses(prefix)
        }
    }
}

fun ClassLoader.scanForClasses(prefix: String) : List<Class<*>> {
    val urls = arrayListOf<URL>()
    val clazzz = arrayListOf<Class<*>>()
    val path = prefix.replace(".", "/")
    val enumeration = this.getResources(path)
    while(enumeration.hasMoreElements()) {
        urls.add(enumeration.nextElement())
    }
    clazzz.addAll(urls.map {
        it.scanForClasses(prefix, this@scanForClasses)
    }.flatten())
    return clazzz
}

private fun URL.scanForClasses(prefix: String = "", classLoader: ClassLoader): List<Class<*>> = when (protocol) {
    "jar" -> JarFile(urlDecode(toExternalForm().substringAfter("file:").substringBeforeLast("!"))).scanForClasses(prefix, classLoader)
    else -> File(urlDecode(path)).scanForClasses(prefix, classLoader)
}

private fun String.packageToPath() = replace(".", File.separator) + File.separator

private fun isAnonClass(name: String): Boolean {
    var idx = name.indexOf('$')

    while (idx >= 0) {
        if (idx + 1 < name.length && name[idx + 1] in '0'..'9') return true
        idx = name.indexOf('$', idx + 1)
    }

    return false
}

val scanLogger = LoggerFactory.getLogger("Kotlinx.Reflect.Scanner")!!

private fun File.scanForClasses(prefix: String, classLoader: ClassLoader): List<Class<*>> {
    scanLogger.debug("Scanning classes in ${this.absolutePath}")
    val path = prefix.packageToPath()
    return walk().filter {
        it.isFile && it.extension == "class" && !isAnonClass(it.name) && it.absolutePath.contains(path)
    }.mapNotNull {
        fun substringAfterPrefix() =
                if (it.absolutePath.replace(prefix, "").length == it.absolutePath.length - prefix.length) // only one occurrence
                    it.absolutePath.substringAfter(path)
                else
                    it.absolutePath.substringAfterLast(path)

        val className = prefix + "." + substringAfterPrefix().removeSuffix(".class").replace(File.separator, ".")

        try {
            scanLogger.debug("Loading class: $className")
            classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            scanLogger.error("Scan for classes not found requested class: $className", e)
            null
        }
    }.toList()
}

private fun JarFile.scanForClasses(prefix: String, classLoader: ClassLoader): List<Class<*>> {
    val classes = arrayListOf<Class<*>>()
    val path = prefix.replace(".", "/") + "/"
    val entries = this.entries()
    while(entries.hasMoreElements()) {
        entries.nextElement().let {
            if (!it.isDirectory && it.name.endsWith(".class") && it.name.startsWith(path) && !isAnonClass(it.name)) {
                val className = prefix + "." + it.name.substringAfter(path).removeSuffix(".class").replace("/", ".")
                try {
                    classLoader.loadClass(className)?.let {
                        classes.add(it)
                    }
                } catch (e : ClassNotFoundException) {
                    scanLogger.error("Scan for classes not found requested class: $className", e)
                }
            }
        }
    }
    return classes
}

@Suppress("UNCHECKED_CAST")
fun <T> Iterable<Class<*>>.filterIsAssignable(clazz: Class<T>): List<Class<T>> = filter { clazz.isAssignableFrom(it) } as List<Class<T>>

@Suppress("UNCHECKED_CAST")
inline fun <reified T: Any> Iterable<Class<*>>.filterIsAssignable(): List<Class<T>> = filterIsAssignable(T::class.java)