package org.cygnusx1.openbu.bambuddy

/**
 * What the printer cards need from wherever the printers live: the Bambuddy server
 * ([BambuddyClient]) or a direct network connection to the printers
 * ([org.cygnusx1.openbu.direct.DirectBackend]). All calls block; run them off the main thread.
 */
interface PrinterBackend {
    /** True for direct printer connections; Bambuddy-only features (files, power, ...) are hidden. */
    val isDirect: Boolean

    fun listPrinters(): List<BbPrinter>
    fun status(printerId: Int): BbStatus

    fun setBedTemp(id: Int, target: Int)
    /** nozzle: 0 = default (the right nozzle on the H2D), 1 = left. */
    fun setNozzleTemp(id: Int, target: Int, nozzle: Int = 0)
    fun setChamberTemp(id: Int, target: Int)
    /** fan: "part", "aux" or "chamber"; speed in percent. */
    fun setFanSpeed(id: Int, fan: String, percent: Int)
    /** mode: 1 silent, 2 standard, 3 sport, 4 ludicrous. */
    fun setPrintSpeed(id: Int, mode: Int)
    fun setChamberLight(id: Int, on: Boolean)
    fun pause(id: Int)
    fun resume(id: Int)
    fun stop(id: Int)
    fun clearPlate(id: Int)
    fun printObjects(id: Int, reload: Boolean = true): BbObjects
    fun skipObjects(id: Int, objectIds: List<Int>)

    // Manual moves (mm, relative). The printer does not check travel limits for these.
    fun jogXY(id: Int, x: Int, y: Int)
    fun jogZ(id: Int, distance: Int)
    fun jogExtruder(id: Int, distance: Int)
    fun homeAll(id: Int)
}
