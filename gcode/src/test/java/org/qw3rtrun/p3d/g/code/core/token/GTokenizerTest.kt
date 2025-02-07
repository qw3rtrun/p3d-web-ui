package org.qw3rtrun.p3d.g.code.core.token

import org.junit.jupiter.api.Test

private const val BEFORE_GCODE =
    "M140 S{first_layer_bed_temperature[0]} ;Запустить нагрев стола до указанной в профиле филамента температуры\n" +
            "M104 S150 ;Запустить нагрев хотэнда до 150 градусов\n" +
            "G28 ;Припарковать все оси\n" +
            "M104 S{first_layer_temperature[0]} ;Запустить нагрев хотэнда до указанной в профиле филамента температуры\n" +
            "M190 S{first_layer_bed_temperature[0]} ;Ожидать нагрева стола до указанной в профиле филамента температуры\n" +
            "M109 S{first_layer_temperature[0]} ;Ожидать нагрева хотэнда до указанной в профиле филамента температуры\n" +
            "G90 ;Установить абсолютную систему координат для всех осей\n" +
            "M220 S100 ;Установить поток в прошивке на 100%\n" +
            "M221 S100 ;Установить множитель скорости в прошивке на 100%"

private const val AFTER_GCODE = "M140 S0 ;Выключить нагрев стола\n" +
        "M106 S0 ;Выключить вентилятор обдува модели\n" +
        "G91 ;Относительная система координат\n" +
        "G1 E-5 F1800 ;Сделать откат 5мм\n" +
        "M104 S0 ;Выключить нагрев хотэнда\n" +
        "G1 Z0.2 F300 ;Поднять печатающую голову на 0.2 мм\n" +
        "G90 ;Абсолютная система координат\n" +
        "G1 X5 Y5 F6000 ;Переехать в координату 5;5\n" +
        "M84 ;Выключить моторы"

class GTokenizerTest {

    private val tokenizer = GTokenizer();

    private fun line(gcode: String) = tokenizer.parse(gcode).toList()

    private fun reprint(gcode: String) = tokenizer.parse(gcode).map { it.rawText() }.reduce { x, y -> "$x$y" }

    @Test
    fun simple() {
        println(line("M155 F1 X1.05\n"))
        println(line("M155F1X1.05 \n"))
        println(line("M11 I   \"Hello World\""))
        println(line("M11I\"Hello World\""))
        println(line("M11I\"Hello \"\"World\"\"\""))
        println(line("M155\n \"Hello World\";comment\"a"))
        println(line("M{SHOW_MGS}I\"Hello \"\"World\"\"\""))
        println(line("M{SET_TEMP} F{CURRENT}X1.05\n"))
        println(line("M{SET_TEMP} (Это \"переменные\" в (в рантайме)) F{CURRENT}X1.05\n"))
        println(line("M11 I   \"Hello World\"M155 \"Hello World\";comment\"\n"))
    }

    @Test
    fun text() {
        println(line(AFTER_GCODE))
        println(line(BEFORE_GCODE))
    }

    @Test
    fun reprint() {
        assert(reprint(AFTER_GCODE) == AFTER_GCODE)
        assert(reprint(BEFORE_GCODE) == BEFORE_GCODE)
    }

    @Test
    fun testEnd() {
        reprint("M{")
        reprint("M\"")
        reprint("M\"asd")
        reprint("M1")
    }

}
