package org.qw3rtrun.p3d.g.marlin

import org.qw3rtrun.p3d.g.code.core.token.GCommand
import org.qw3rtrun.p3d.g.code.core.token.GInt
import org.qw3rtrun.p3d.g.code.core.token.GParameterWord
import org.qw3rtrun.p3d.g.code.core.token.GWord
import org.qw3rtrun.p3d.g.code.dsl.GRQ
import org.qw3rtrun.p3d.g.code.dsl.M
import org.qw3rtrun.p3d.g.code.dsl.word

/**
 * M105 T<index>
 *
 * @link
 * @see <a href="https://marlinfw.org/docs/gcode/M105.html">MarlinFirmare M105 doc</a>
 */
data class ReportHotendTemperature(val index: Int) : GRQ<ReportHotendTemperature> {
    constructor() : this(0)

    override fun encode(): GCommand {
        return M(105, word('T', this.index))
    }

    override fun head(): GParameterWord<*> {
        return M(105).head
    }

    override fun decodeParams(params: List<GWord>): ReportHotendTemperature {
        val index = params.find { it is GParameterWord<*> && it.id.isLetter('M') } as GParameterWord<*>
        return ReportHotendTemperature(if (index.value is GInt) index.value.int else 0)
    }

    override fun toString(): String {
        return javaClass.getSimpleName() + "(" + encode() + ')'
    }

}

object MarlinG {
    fun m105(index: Int?): ReportHotendTemperature {
        return ReportHotendTemperature(index ?: 0)
    }

    fun m105(): ReportHotendTemperature {
        return ReportHotendTemperature(0)
    }

    fun tempReport(tool: Int? = null) = m105(tool)
    fun tempReport() = m105()
}