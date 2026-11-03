package xyz.wagyourtail.unimined.transform.misc

import xyz.wagyourtail.unimined.transform.spi.TransformerClaim
import xyz.wagyourtail.unimined.transform.spi.TransformerSource

/** ServiceLoader source — exposes the `custom` fallback claim. */
class CustomTransformerSource : TransformerSource {

    override fun transformerClaims(): List<TransformerClaim> =
        listOf(CustomTransformerClaim())

}
