package app.pictext.image

/** 错误分类，所有异常都必须收敛到这里。 */
enum class FailureKind {
    UNREADABLE,
    UNSUPPORTED_FORMAT,
    DECODE_FAILED,
    INTERNAL,
}

class DecodeException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
