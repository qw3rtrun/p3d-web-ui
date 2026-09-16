// GENERATED FILE - do not edit.  Regenerate with: python3 tools/marlin/gen_mcommands.py

package org.qw3rtrun.p3d.g.marlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.qw3rtrun.p3d.g.code.core.GEncoder
import org.qw3rtrun.p3d.g.code.dsl.M
import java.math.BigDecimal

/**
 * Generated cover for the generated commands. Three claims, one per test:
 *
 * 1. a command with nothing set encodes to exactly its code, so no parameter leaks onto
 *    the wire uninvited;
 * 2. a command with **every** parameter set names every one of its letters, and survives a
 *    round trip through encode and decode;
 * 3. the registry resolves each command's own head and nothing else.
 */
class MarlinCommandsTest {

    @Test
    fun `every command has a prototype`() {
        assertEquals(295, MarlinCommands.all.size)
        assertEquals(MarlinCommands.all.size, MarlinCommands.decoders.size)
        assertEquals(MarlinCommands.all.size, MarlinCommands.info.size)
        // Fewer heads than classes, by exactly the variants Marlin documents separately.
        assertEquals(
            287,
            MarlinCommands.decoders.map { it.head() }.toSet().size,
        )
        assertEquals(listOf("G29", "G34", "M665", "M666"), MarlinCommands.ambiguousCodes)
    }

    @Test
    fun `a bare command encodes to just its code`() {
        assertEquals("G0", GEncoder.encode(LinearMoveG0().encode()))
        assertEquals("G1", GEncoder.encode(LinearMoveG1().encode()))
        assertEquals("G2", GEncoder.encode(ArcOrCircleMoveG2().encode()))
        assertEquals("G3", GEncoder.encode(ArcOrCircleMoveG3().encode()))
        assertEquals("G4", GEncoder.encode(Dwell().encode()))
        assertEquals("G5", GEncoder.encode(BezierCubicSplineMove().encode()))
        assertEquals("G6", GEncoder.encode(DirectStepperMove().encode()))
        assertEquals("G10", GEncoder.encode(Retract().encode()))
        assertEquals("G11", GEncoder.encode(Recover().encode()))
        assertEquals("G12", GEncoder.encode(CleanTheNozzle().encode()))
        assertEquals("G17", GEncoder.encode(CNCWorkspacePlanesG17().encode()))
        assertEquals("G18", GEncoder.encode(CNCWorkspacePlanesG18().encode()))
        assertEquals("G19", GEncoder.encode(CNCWorkspacePlanesG19().encode()))
        assertEquals("G20", GEncoder.encode(InchUnits().encode()))
        assertEquals("G21", GEncoder.encode(MillimeterUnits().encode()))
        assertEquals("G26", GEncoder.encode(MeshValidationPattern().encode()))
        assertEquals("G27", GEncoder.encode(ParkToolhead().encode()))
        assertEquals("G28", GEncoder.encode(AutoHome().encode()))
        assertEquals("G29", GEncoder.encode(BedLeveling3Point().encode()))
        assertEquals("G29", GEncoder.encode(BedLevelingBilinear().encode()))
        assertEquals("G29", GEncoder.encode(BedLevelingLinear().encode()))
        assertEquals("G29", GEncoder.encode(BedLevelingManual().encode()))
        assertEquals("G29", GEncoder.encode(BedLevelingUnified().encode()))
        assertEquals("G29", GEncoder.encode(BedLeveling().encode()))
        assertEquals("G30", GEncoder.encode(SingleZProbe().encode()))
        assertEquals("G31", GEncoder.encode(DockSled().encode()))
        assertEquals("G32", GEncoder.encode(UndockSled().encode()))
        assertEquals("G33", GEncoder.encode(DeltaAutoCalibration().encode()))
        assertEquals("G34", GEncoder.encode(MechanicalGantryCalibration().encode()))
        assertEquals("G34", GEncoder.encode(ZSteppersAutoAlignment().encode()))
        assertEquals("G35", GEncoder.encode(TrammingAssistant().encode()))
        assertEquals("G38.2", GEncoder.encode(ProbeTargetG38_2().encode()))
        assertEquals("G38.3", GEncoder.encode(ProbeTargetG38_3().encode()))
        assertEquals("G38.4", GEncoder.encode(ProbeTargetG38_4().encode()))
        assertEquals("G38.5", GEncoder.encode(ProbeTargetG38_5().encode()))
        assertEquals("G42", GEncoder.encode(MoveToMeshCoordinate().encode()))
        assertEquals("G53", GEncoder.encode(MoveInMachineCoordinates().encode()))
        assertEquals("G54", GEncoder.encode(SelectWorkspaceG54().encode()))
        assertEquals("G55", GEncoder.encode(SelectWorkspaceG55().encode()))
        assertEquals("G56", GEncoder.encode(SelectWorkspaceG56().encode()))
        assertEquals("G57", GEncoder.encode(SelectWorkspaceG57().encode()))
        assertEquals("G58", GEncoder.encode(SelectWorkspaceG58().encode()))
        assertEquals("G59", GEncoder.encode(SelectWorkspaceG59().encode()))
        assertEquals("G59.1", GEncoder.encode(SelectWorkspaceG59_1().encode()))
        assertEquals("G59.2", GEncoder.encode(SelectWorkspaceG59_2().encode()))
        assertEquals("G59.3", GEncoder.encode(SelectWorkspaceG59_3().encode()))
        assertEquals("G60", GEncoder.encode(StoredPositions().encode()))
        assertEquals("G61", GEncoder.encode(ReturnToSavedPosition().encode()))
        assertEquals("G76", GEncoder.encode(ProbeTemperatureCalibration().encode()))
        assertEquals("G80", GEncoder.encode(CancelCurrentMotionMode().encode()))
        assertEquals("G90", GEncoder.encode(AbsolutePositioning().encode()))
        assertEquals("G91", GEncoder.encode(RelativePositioning().encode()))
        assertEquals("G92", GEncoder.encode(SetPosition().encode()))
        assertEquals("G425", GEncoder.encode(BacklashAndToolheadOffsetCalibration().encode()))
        assertEquals("M0", GEncoder.encode(UnconditionalStopM0().encode()))
        assertEquals("M1", GEncoder.encode(UnconditionalStopM1().encode()))
        assertEquals("M3", GEncoder.encode(SpindleCWLaserOn().encode()))
        assertEquals("M4", GEncoder.encode(SpindleCCWLaserOn().encode()))
        assertEquals("M5", GEncoder.encode(SpindleLaserOff().encode()))
        assertEquals("M7", GEncoder.encode(CoolantControlsM7().encode()))
        assertEquals("M8", GEncoder.encode(CoolantControlsM8().encode()))
        assertEquals("M9", GEncoder.encode(CoolantControlsM9().encode()))
        assertEquals("M10", GEncoder.encode(VacuumBlowerControlM10().encode()))
        assertEquals("M11", GEncoder.encode(VacuumBlowerControlM11().encode()))
        assertEquals("M16", GEncoder.encode(ExpectedPrinterCheck().encode()))
        assertEquals("M17", GEncoder.encode(EnableSteppers().encode()))
        assertEquals("M18", GEncoder.encode(DisableSteppersM18().encode()))
        assertEquals("M20", GEncoder.encode(ListSDCard().encode()))
        assertEquals("M21", GEncoder.encode(InitSDCard().encode()))
        assertEquals("M22", GEncoder.encode(ReleaseSDCard().encode()))
        assertEquals("M23", GEncoder.encode(SelectSDFile().encode()))
        assertEquals("M24", GEncoder.encode(StartOrResumeSDPrint().encode()))
        assertEquals("M25", GEncoder.encode(PauseSDPrint().encode()))
        assertEquals("M26", GEncoder.encode(SetSDPosition().encode()))
        assertEquals("M27", GEncoder.encode(ReportSDPrintStatus().encode()))
        assertEquals("M28", GEncoder.encode(StartSDWrite().encode()))
        assertEquals("M29", GEncoder.encode(StopSDWrite().encode()))
        assertEquals("M30", GEncoder.encode(DeleteSDFile().encode()))
        assertEquals("M31", GEncoder.encode(ReportPrintTime().encode()))
        assertEquals("M32", GEncoder.encode(SelectAndStart().encode()))
        assertEquals("M33", GEncoder.encode(GetLongPath().encode()))
        assertEquals("M34", GEncoder.encode(SDCardSorting().encode()))
        assertEquals("M42", GEncoder.encode(SetPinState().encode()))
        assertEquals("M43", GEncoder.encode(PinsDebugging().encode()))
        assertEquals("M48", GEncoder.encode(ProbeRepeatabilityTest().encode()))
        assertEquals("M73", GEncoder.encode(SetPrintProgress().encode()))
        assertEquals("M75", GEncoder.encode(StartPrintJobTimer().encode()))
        assertEquals("M76", GEncoder.encode(PausePrintJobTimer().encode()))
        assertEquals("M77", GEncoder.encode(StopPrintJobTimer().encode()))
        assertEquals("M78", GEncoder.encode(PrintJobStats().encode()))
        assertEquals("M80", GEncoder.encode(PowerOn().encode()))
        assertEquals("M81", GEncoder.encode(PowerOff().encode()))
        assertEquals("M82", GEncoder.encode(EAbsolute().encode()))
        assertEquals("M83", GEncoder.encode(ERelative().encode()))
        assertEquals("M84", GEncoder.encode(DisableSteppersM84().encode()))
        assertEquals("M85", GEncoder.encode(InactivityShutdown().encode()))
        assertEquals("M86", GEncoder.encode(HotendIdleTimeout().encode()))
        assertEquals("M87", GEncoder.encode(DisableHotendIdleTimeout().encode()))
        assertEquals("M92", GEncoder.encode(SetAxisStepsPerUnit().encode()))
        assertEquals("M100", GEncoder.encode(FreeMemory().encode()))
        assertEquals("M102", GEncoder.encode(ConfigureBedDistanceSensor().encode()))
        assertEquals("M104", GEncoder.encode(SetHotendTemperature().encode()))
        assertEquals("M105", GEncoder.encode(ReportHotendTemperature().encode()))
        assertEquals("M106", GEncoder.encode(SetFanSpeed().encode()))
        assertEquals("M107", GEncoder.encode(FanOff().encode()))
        assertEquals("M108", GEncoder.encode(BreakAndContinue().encode()))
        assertEquals("M109", GEncoder.encode(WaitForHotendTemperature().encode()))
        assertEquals("M110", GEncoder.encode(SetGetLineNumber().encode()))
        assertEquals("M111", GEncoder.encode(DebugLevel().encode()))
        assertEquals("M112", GEncoder.encode(FullShutdown().encode()))
        assertEquals("M113", GEncoder.encode(HostKeepalive().encode()))
        assertEquals("M114", GEncoder.encode(GetCurrentPosition().encode()))
        assertEquals("M115", GEncoder.encode(FirmwareInfo().encode()))
        assertEquals("M117", GEncoder.encode(SetLCDMessage().encode()))
        assertEquals("M118", GEncoder.encode(SerialPrint().encode()))
        assertEquals("M119", GEncoder.encode(EndstopStates().encode()))
        assertEquals("M120", GEncoder.encode(EnableEndstops().encode()))
        assertEquals("M121", GEncoder.encode(DisableEndstops().encode()))
        assertEquals("M122", GEncoder.encode(TMCDebugging().encode()))
        assertEquals("M123", GEncoder.encode(FanTachometers().encode()))
        assertEquals("M125", GEncoder.encode(ParkHead().encode()))
        assertEquals("M126", GEncoder.encode(Baricuda1Open().encode()))
        assertEquals("M127", GEncoder.encode(Baricuda1Close().encode()))
        assertEquals("M128", GEncoder.encode(Baricuda2Open().encode()))
        assertEquals("M129", GEncoder.encode(Baricuda2Close().encode()))
        assertEquals("M140", GEncoder.encode(SetBedTemperature().encode()))
        assertEquals("M141", GEncoder.encode(SetChamberTemperature().encode()))
        assertEquals("M143", GEncoder.encode(SetLaserCoolerTemperature().encode()))
        assertEquals("M145", GEncoder.encode(SetMaterialPreset().encode()))
        assertEquals("M149", GEncoder.encode(SetTemperatureUnits().encode()))
        assertEquals("M150", GEncoder.encode(SetRGBWColor().encode()))
        assertEquals("M154", GEncoder.encode(PositionAutoReport().encode()))
        assertEquals("M155", GEncoder.encode(TemperatureAutoReport().encode()))
        assertEquals("M163", GEncoder.encode(SetMixFactor().encode()))
        assertEquals("M164", GEncoder.encode(SaveMix().encode()))
        assertEquals("M165", GEncoder.encode(SetMix().encode()))
        assertEquals("M166", GEncoder.encode(GradientMix().encode()))
        assertEquals("M190", GEncoder.encode(WaitForBedTemperature().encode()))
        assertEquals("M191", GEncoder.encode(WaitForChamberTemperature().encode()))
        assertEquals("M192", GEncoder.encode(WaitForProbeTemperature().encode()))
        assertEquals("M193", GEncoder.encode(WaitForLaserCoolerTemperature().encode()))
        assertEquals("M200", GEncoder.encode(VolumetricExtrusionDiameter().encode()))
        assertEquals("M201", GEncoder.encode(PrintTravelMoveLimits().encode()))
        assertEquals("M203", GEncoder.encode(SetMaxFeedrate().encode()))
        assertEquals("M204", GEncoder.encode(SetStartingAcceleration().encode()))
        assertEquals("M205", GEncoder.encode(SetAdvancedSettings().encode()))
        assertEquals("M206", GEncoder.encode(SetHomeOffsets().encode()))
        assertEquals("M207", GEncoder.encode(FirmwareRetractionSettings().encode()))
        assertEquals("M208", GEncoder.encode(FirmwareRecoverSettings().encode()))
        assertEquals("M209", GEncoder.encode(SetAutoRetract().encode()))
        assertEquals("M210", GEncoder.encode(HomingFeedrate().encode()))
        assertEquals("M211", GEncoder.encode(SoftwareEndstops().encode()))
        assertEquals("M217", GEncoder.encode(FilamentSwapParameters().encode()))
        assertEquals("M218", GEncoder.encode(SetHotendOffset().encode()))
        assertEquals("M220", GEncoder.encode(SetFeedratePercentage().encode()))
        assertEquals("M221", GEncoder.encode(SetFlowPercentage().encode()))
        assertEquals("M226", GEncoder.encode(WaitForPinState().encode()))
        assertEquals("M240", GEncoder.encode(TriggerCamera().encode()))
        assertEquals("M250", GEncoder.encode(LCDContrast().encode()))
        assertEquals("M255", GEncoder.encode(LCDSleepBacklightTimeout().encode()))
        assertEquals("M256", GEncoder.encode(LCDBrightness().encode()))
        assertEquals("M260", GEncoder.encode(I2CSend().encode()))
        assertEquals("M261", GEncoder.encode(I2CRequest().encode()))
        assertEquals("M265", GEncoder.encode(ScanI2CBus().encode()))
        assertEquals("M280", GEncoder.encode(ServoPosition().encode()))
        assertEquals("M281", GEncoder.encode(EditServoAngles().encode()))
        assertEquals("M282", GEncoder.encode(DetachServo().encode()))
        assertEquals("M290", GEncoder.encode(Babystep().encode()))
        assertEquals("M300", GEncoder.encode(PlayTone().encode()))
        assertEquals("M301", GEncoder.encode(SetHotendPID().encode()))
        assertEquals("M302", GEncoder.encode(ColdExtrude().encode()))
        assertEquals("M303", GEncoder.encode(PIDAutotune().encode()))
        assertEquals("M304", GEncoder.encode(SetBedPID().encode()))
        assertEquals("M305", GEncoder.encode(UserThermistorParameters().encode()))
        assertEquals("M306", GEncoder.encode(ModelPredictiveTempControl().encode()))
        assertEquals("M309", GEncoder.encode(SetChamberPID().encode()))
        assertEquals("M350", GEncoder.encode(SetMicroStepping().encode()))
        assertEquals("M351", GEncoder.encode(SetMicrostepPins().encode()))
        assertEquals("M355", GEncoder.encode(CaseLightControl().encode()))
        assertEquals("M360", GEncoder.encode(SCARAThetaA().encode()))
        assertEquals("M361", GEncoder.encode(SCARAThetaB().encode()))
        assertEquals("M362", GEncoder.encode(SCARAPsiA().encode()))
        assertEquals("M363", GEncoder.encode(SCARAPsiB().encode()))
        assertEquals("M364", GEncoder.encode(SCARAPsiC().encode()))
        assertEquals("M380", GEncoder.encode(ActivateSolenoid().encode()))
        assertEquals("M381", GEncoder.encode(DeactivateSolenoids().encode()))
        assertEquals("M400", GEncoder.encode(FinishMoves().encode()))
        assertEquals("M401", GEncoder.encode(DeployProbe().encode()))
        assertEquals("M402", GEncoder.encode(StowProbe().encode()))
        assertEquals("M403", GEncoder.encode(MMU2FilamentType().encode()))
        assertEquals("M404", GEncoder.encode(FilamentWidthSensorNominalDiameter().encode()))
        assertEquals("M405", GEncoder.encode(FilamentWidthSensorOn().encode()))
        assertEquals("M406", GEncoder.encode(FilamentWidthSensorOff().encode()))
        assertEquals("M407", GEncoder.encode(ReadFilamentWidth().encode()))
        assertEquals("M410", GEncoder.encode(Quickstop().encode()))
        assertEquals("M412", GEncoder.encode(FilamentRunout().encode()))
        assertEquals("M413", GEncoder.encode(PowerLossRecovery().encode()))
        assertEquals("M414", GEncoder.encode(LCDLanguage().encode()))
        assertEquals("M420", GEncoder.encode(BedLevelingState().encode()))
        assertEquals("M421", GEncoder.encode(SetMeshValue().encode()))
        assertEquals("M422", GEncoder.encode(SetZMotorXY().encode()))
        assertEquals("M423", GEncoder.encode(XTwistCompensation().encode()))
        assertEquals("M425", GEncoder.encode(BacklashCompensation().encode()))
        assertEquals("M428", GEncoder.encode(HomeOffsetsHere().encode()))
        assertEquals("M430", GEncoder.encode(PowerMonitor().encode()))
        assertEquals("M486", GEncoder.encode(CancelObjects().encode()))
        assertEquals("M493", GEncoder.encode(FixedTimeMotion().encode()))
        assertEquals("M494", GEncoder.encode(FTMotionTrajectorySmoothing().encode()))
        assertEquals("M500", GEncoder.encode(SaveSettings().encode()))
        assertEquals("M501", GEncoder.encode(RestoreSettings().encode()))
        assertEquals("M502", GEncoder.encode(FactoryReset().encode()))
        assertEquals("M503", GEncoder.encode(ReportSettings().encode()))
        assertEquals("M504", GEncoder.encode(ValidateEEPROMContents().encode()))
        assertEquals("M510", GEncoder.encode(LockMachine().encode()))
        assertEquals("M511", GEncoder.encode(UnlockMachine().encode()))
        assertEquals("M512", GEncoder.encode(SetPasscode().encode()))
        assertEquals("M524", GEncoder.encode(AbortSDPrint().encode()))
        assertEquals("M540", GEncoder.encode(EndstopsAbortSD().encode()))
        assertEquals("M550", GEncoder.encode(MachineName().encode()))
        assertEquals("M552", GEncoder.encode(EthernetIPAddressNetworkIF().encode()))
        assertEquals("M553", GEncoder.encode(EthernetSubnetMask().encode()))
        assertEquals("M554", GEncoder.encode(EthernetGatewayIPAddress().encode()))
        assertEquals("M569", GEncoder.encode(SetTMCSteppingMode().encode()))
        assertEquals("M575", GEncoder.encode(SerialBaudRate().encode()))
        assertEquals("M592", GEncoder.encode(NonlinearExtrusionControl().encode()))
        assertEquals("M593", GEncoder.encode(ZVInputShaping().encode()))
        assertEquals("M600", GEncoder.encode(FilamentChange().encode()))
        assertEquals("M603", GEncoder.encode(ConfigureFilamentChange().encode()))
        assertEquals("M605", GEncoder.encode(MultiNozzleMode().encode()))
        assertEquals("M665", GEncoder.encode(SCARAConfiguration().encode()))
        assertEquals("M665", GEncoder.encode(DeltaConfiguration().encode()))
        assertEquals("M666", GEncoder.encode(DualEndstopOffsets().encode()))
        assertEquals("M666", GEncoder.encode(SetDeltaEndstopAdjustments().encode()))
        assertEquals("M672", GEncoder.encode(DuetSmartEffectorSensitivity().encode()))
        assertEquals("M701", GEncoder.encode(LoadFilament().encode()))
        assertEquals("M702", GEncoder.encode(UnloadFilament().encode()))
        assertEquals("M710", GEncoder.encode(ControllerFanSettings().encode()))
        assertEquals("M808", GEncoder.encode(RepeatMarker().encode()))
        assertEquals("M810", GEncoder.encode(GCodeMacrosM810().encode()))
        assertEquals("M811", GEncoder.encode(GCodeMacrosM811().encode()))
        assertEquals("M812", GEncoder.encode(GCodeMacrosM812().encode()))
        assertEquals("M813", GEncoder.encode(GCodeMacrosM813().encode()))
        assertEquals("M814", GEncoder.encode(GCodeMacrosM814().encode()))
        assertEquals("M815", GEncoder.encode(GCodeMacrosM815().encode()))
        assertEquals("M816", GEncoder.encode(GCodeMacrosM816().encode()))
        assertEquals("M817", GEncoder.encode(GCodeMacrosM817().encode()))
        assertEquals("M818", GEncoder.encode(GCodeMacrosM818().encode()))
        assertEquals("M819", GEncoder.encode(GCodeMacrosM819().encode()))
        assertEquals("M820", GEncoder.encode(ReportGCodeMacros().encode()))
        assertEquals("M851", GEncoder.encode(XYZProbeOffset().encode()))
        assertEquals("M852", GEncoder.encode(BedSkewCompensation().encode()))
        assertEquals("M860", GEncoder.encode(I2CPositionEncodersM860().encode()))
        assertEquals("M861", GEncoder.encode(I2CPositionEncodersM861().encode()))
        assertEquals("M862", GEncoder.encode(I2CPositionEncodersM862().encode()))
        assertEquals("M863", GEncoder.encode(I2CPositionEncodersM863().encode()))
        assertEquals("M864", GEncoder.encode(I2CPositionEncodersM864().encode()))
        assertEquals("M865", GEncoder.encode(I2CPositionEncodersM865().encode()))
        assertEquals("M866", GEncoder.encode(I2CPositionEncodersM866().encode()))
        assertEquals("M867", GEncoder.encode(I2CPositionEncodersM867().encode()))
        assertEquals("M868", GEncoder.encode(I2CPositionEncodersM868().encode()))
        assertEquals("M869", GEncoder.encode(I2CPositionEncodersM869().encode()))
        assertEquals("M871", GEncoder.encode(ProbeTemperatureConfig().encode()))
        assertEquals("M876", GEncoder.encode(HandlePromptResponse().encode()))
        assertEquals("M900", GEncoder.encode(LinearAdvanceFactor().encode()))
        assertEquals("M906", GEncoder.encode(StepperMotorCurrent().encode()))
        assertEquals("M907", GEncoder.encode(TrimpotStepperMotorCurrent().encode()))
        assertEquals("M908", GEncoder.encode(SetTrimpotPins().encode()))
        assertEquals("M909", GEncoder.encode(ReportDACStepperCurrent().encode()))
        assertEquals("M910", GEncoder.encode(CommitDACToEEPROM().encode()))
        assertEquals("M911", GEncoder.encode(TMCOTPreWarnCondition().encode()))
        assertEquals("M912", GEncoder.encode(ClearTMCOTPreWarn().encode()))
        assertEquals("M913", GEncoder.encode(SetHybridThresholdSpeed().encode()))
        assertEquals("M914", GEncoder.encode(TMCBumpSensitivity().encode()))
        assertEquals("M915", GEncoder.encode(TMCZAxisCalibration().encode()))
        assertEquals("M916", GEncoder.encode(L6474ThermalWarningTest().encode()))
        assertEquals("M917", GEncoder.encode(L6474OvercurrentWarningTest().encode()))
        assertEquals("M918", GEncoder.encode(L6474SpeedWarningTest().encode()))
        assertEquals("M919", GEncoder.encode(TMCChopperTiming().encode()))
        assertEquals("M920", GEncoder.encode(TMCHomingCurrent().encode()))
        assertEquals("M928", GEncoder.encode(StartSDLogging().encode()))
        assertEquals("M951", GEncoder.encode(MagneticParkingExtruder().encode()))
        assertEquals("M993", GEncoder.encode(BackUpFlashSettingsToSD().encode()))
        assertEquals("M994", GEncoder.encode(RestoreFlashFromSD().encode()))
        assertEquals("M995", GEncoder.encode(TouchScreenCalibration().encode()))
        assertEquals("M997", GEncoder.encode(FirmwareUpdate().encode()))
        assertEquals("M999", GEncoder.encode(STOPRestart().encode()))
        assertEquals("M7219", GEncoder.encode(MAX7219Control().encode()))
        assertEquals("T0", GEncoder.encode(SelectOrReportToolT0().encode()))
        assertEquals("T1", GEncoder.encode(SelectOrReportToolT1().encode()))
        assertEquals("T2", GEncoder.encode(SelectOrReportToolT2().encode()))
        assertEquals("T3", GEncoder.encode(SelectOrReportToolT3().encode()))
        assertEquals("T4", GEncoder.encode(SelectOrReportToolT4().encode()))
        assertEquals("T5", GEncoder.encode(SelectOrReportToolT5().encode()))
        assertEquals("T6", GEncoder.encode(SelectOrReportToolT6().encode()))
        assertEquals("T7", GEncoder.encode(SelectOrReportToolT7().encode()))
    }

    @Test
    fun `a bare command round-trips through its own class`() {
        // Through the class's own decoder and not the registry: six classes answer to
        // `G29`, so the registry can only return one of them and equality would fail for
        // the other five. `all` and `decoders` are index-aligned, so zip pairs each
        // command with its own companion.
        for ((proto, decoder) in MarlinCommands.all.zip(MarlinCommands.decoders)) {
            assertEquals(proto, decoder.decodeParams(proto.encode().params)) {
                "round trip failed for " + GEncoder.encode(proto.encode())
            }
        }
    }

    @Test
    fun `the registry resolves a head it has and no other`() {
        assertNotNull(MarlinCommands.decode(M(105)))
        // `M0105` is the same number written differently, and the lexeme is part of a
        // number's identity here, so it deliberately does not resolve.
        assertNull(MarlinCommands.decode(M("0105")))
        assertNull(MarlinCommands.decode(M(998)))
    }

    @Test
    fun `every parameter is written and read back 1`() {
        LinearMoveG0(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5"), e = BigDecimal("1.5"), rate = BigDecimal("1.5"), power = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G0 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G0 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G0 missing Z in $text" }
            assertTrue(text.contains(" A")) { "G0 missing A in $text" }
            assertTrue(text.contains(" B")) { "G0 missing B in $text" }
            assertTrue(text.contains(" C")) { "G0 missing C in $text" }
            assertTrue(text.contains(" U")) { "G0 missing U in $text" }
            assertTrue(text.contains(" V")) { "G0 missing V in $text" }
            assertTrue(text.contains(" W")) { "G0 missing W in $text" }
            assertTrue(text.contains(" E")) { "G0 missing E in $text" }
            assertTrue(text.contains(" F")) { "G0 missing F in $text" }
            assertTrue(text.contains(" S")) { "G0 missing S in $text" }
            assertEquals(it, LinearMoveG0.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        LinearMoveG1(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5"), e = BigDecimal("1.5"), rate = BigDecimal("1.5"), power = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G1 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G1 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G1 missing Z in $text" }
            assertTrue(text.contains(" A")) { "G1 missing A in $text" }
            assertTrue(text.contains(" B")) { "G1 missing B in $text" }
            assertTrue(text.contains(" C")) { "G1 missing C in $text" }
            assertTrue(text.contains(" U")) { "G1 missing U in $text" }
            assertTrue(text.contains(" V")) { "G1 missing V in $text" }
            assertTrue(text.contains(" W")) { "G1 missing W in $text" }
            assertTrue(text.contains(" E")) { "G1 missing E in $text" }
            assertTrue(text.contains(" F")) { "G1 missing F in $text" }
            assertTrue(text.contains(" S")) { "G1 missing S in $text" }
            assertEquals(it, LinearMoveG1.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ArcOrCircleMoveG2(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5"), offset = BigDecimal("1.5"), j = BigDecimal("1.5"), radius = BigDecimal("1.5"), e = BigDecimal("1.5"), rate = BigDecimal("1.5"), count = 1, power = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G2 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G2 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G2 missing Z in $text" }
            assertTrue(text.contains(" A")) { "G2 missing A in $text" }
            assertTrue(text.contains(" B")) { "G2 missing B in $text" }
            assertTrue(text.contains(" C")) { "G2 missing C in $text" }
            assertTrue(text.contains(" U")) { "G2 missing U in $text" }
            assertTrue(text.contains(" V")) { "G2 missing V in $text" }
            assertTrue(text.contains(" W")) { "G2 missing W in $text" }
            assertTrue(text.contains(" I")) { "G2 missing I in $text" }
            assertTrue(text.contains(" J")) { "G2 missing J in $text" }
            assertTrue(text.contains(" R")) { "G2 missing R in $text" }
            assertTrue(text.contains(" E")) { "G2 missing E in $text" }
            assertTrue(text.contains(" F")) { "G2 missing F in $text" }
            assertTrue(text.contains(" P")) { "G2 missing P in $text" }
            assertTrue(text.contains(" S")) { "G2 missing S in $text" }
            assertEquals(it, ArcOrCircleMoveG2.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ArcOrCircleMoveG3(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5"), offset = BigDecimal("1.5"), j = BigDecimal("1.5"), radius = BigDecimal("1.5"), e = BigDecimal("1.5"), rate = BigDecimal("1.5"), count = 1, power = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G3 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G3 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G3 missing Z in $text" }
            assertTrue(text.contains(" A")) { "G3 missing A in $text" }
            assertTrue(text.contains(" B")) { "G3 missing B in $text" }
            assertTrue(text.contains(" C")) { "G3 missing C in $text" }
            assertTrue(text.contains(" U")) { "G3 missing U in $text" }
            assertTrue(text.contains(" V")) { "G3 missing V in $text" }
            assertTrue(text.contains(" W")) { "G3 missing W in $text" }
            assertTrue(text.contains(" I")) { "G3 missing I in $text" }
            assertTrue(text.contains(" J")) { "G3 missing J in $text" }
            assertTrue(text.contains(" R")) { "G3 missing R in $text" }
            assertTrue(text.contains(" E")) { "G3 missing E in $text" }
            assertTrue(text.contains(" F")) { "G3 missing F in $text" }
            assertTrue(text.contains(" P")) { "G3 missing P in $text" }
            assertTrue(text.contains(" S")) { "G3 missing S in $text" }
            assertEquals(it, ArcOrCircleMoveG3.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        Dwell(time = 1, p = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "G4 missing S in $text" }
            assertTrue(text.contains(" P")) { "G4 missing P in $text" }
            assertEquals(it, Dwell.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BezierCubicSplineMove(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), e = BigDecimal("1.5"), rate = BigDecimal("1.5"), i = BigDecimal("1.5"), j = BigDecimal("1.5"), p = BigDecimal("1.5"), q = BigDecimal("1.5"), power = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G5 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G5 missing Y in $text" }
            assertTrue(text.contains(" E")) { "G5 missing E in $text" }
            assertTrue(text.contains(" F")) { "G5 missing F in $text" }
            assertTrue(text.contains(" I")) { "G5 missing I in $text" }
            assertTrue(text.contains(" J")) { "G5 missing J in $text" }
            assertTrue(text.contains(" P")) { "G5 missing P in $text" }
            assertTrue(text.contains(" Q")) { "G5 missing Q in $text" }
            assertTrue(text.contains(" S")) { "G5 missing S in $text" }
            assertEquals(it, BezierCubicSplineMove.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DirectStepperMove(index = 1, rate = BigDecimal("1.5"), s = BigDecimal("1.5"), direction = 1, y = 1, z = 1, e = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "G6 missing I in $text" }
            assertTrue(text.contains(" R")) { "G6 missing R in $text" }
            assertTrue(text.contains(" S")) { "G6 missing S in $text" }
            assertTrue(text.contains(" X")) { "G6 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G6 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G6 missing Z in $text" }
            assertTrue(text.contains(" E")) { "G6 missing E in $text" }
            assertEquals(it, DirectStepperMove.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        Retract(s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "G10 missing S in $text" }
            assertEquals(it, Retract.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        CleanTheNozzle(p = 1, radius = BigDecimal("1.5"), count = 1, t = 1, x = true, y = true, z = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "G12 missing P in $text" }
            assertTrue(text.contains(" R")) { "G12 missing R in $text" }
            assertTrue(text.contains(" S")) { "G12 missing S in $text" }
            assertTrue(text.contains(" T")) { "G12 missing T in $text" }
            assertTrue(text.contains(" X")) { "G12 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G12 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G12 missing Z in $text" }
            assertEquals(it, CleanTheNozzle.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MeshValidationPattern(temp = 1, c = true, d = true, linear = BigDecimal("1.5"), h = BigDecimal("1.5"), index = 1, k = true, l = BigDecimal("1.5"), o = BigDecimal("1.5"), p = BigDecimal("1.5"), q = BigDecimal("1.5"), r = 1, s = BigDecimal("1.5"), u = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" B")) { "G26 missing B in $text" }
            assertTrue(text.contains(" C")) { "G26 missing C in $text" }
            assertTrue(text.contains(" D")) { "G26 missing D in $text" }
            assertTrue(text.contains(" F")) { "G26 missing F in $text" }
            assertTrue(text.contains(" H")) { "G26 missing H in $text" }
            assertTrue(text.contains(" I")) { "G26 missing I in $text" }
            assertTrue(text.contains(" K")) { "G26 missing K in $text" }
            assertTrue(text.contains(" L")) { "G26 missing L in $text" }
            assertTrue(text.contains(" O")) { "G26 missing O in $text" }
            assertTrue(text.contains(" P")) { "G26 missing P in $text" }
            assertTrue(text.contains(" Q")) { "G26 missing Q in $text" }
            assertTrue(text.contains(" R")) { "G26 missing R in $text" }
            assertTrue(text.contains(" S")) { "G26 missing S in $text" }
            assertTrue(text.contains(" U")) { "G26 missing U in $text" }
            assertTrue(text.contains(" X")) { "G26 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G26 missing Y in $text" }
            assertEquals(it, MeshValidationPattern.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ParkToolhead(p = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "G27 missing P in $text" }
            assertEquals(it, ParkToolhead.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        AutoHome(h = true, l = true, o = true, linear = BigDecimal("1.5"), x = true, y = true, z = true, a = true, b = true, c = true, u = true, v = true, w = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" H")) { "G28 missing H in $text" }
            assertTrue(text.contains(" L")) { "G28 missing L in $text" }
            assertTrue(text.contains(" O")) { "G28 missing O in $text" }
            assertTrue(text.contains(" R")) { "G28 missing R in $text" }
            assertTrue(text.contains(" X")) { "G28 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G28 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G28 missing Z in $text" }
            assertTrue(text.contains(" A")) { "G28 missing A in $text" }
            assertTrue(text.contains(" B")) { "G28 missing B in $text" }
            assertTrue(text.contains(" C")) { "G28 missing C in $text" }
            assertTrue(text.contains(" U")) { "G28 missing U in $text" }
            assertTrue(text.contains(" V")) { "G28 missing V in $text" }
            assertTrue(text.contains(" W")) { "G28 missing W in $text" }
            assertEquals(it, AutoHome.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BedLeveling3Point(a = true, c = true, o = true, q = true, e = true, d = true, j = true, v = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "G29 missing A in $text" }
            assertTrue(text.contains(" C")) { "G29 missing C in $text" }
            assertTrue(text.contains(" O")) { "G29 missing O in $text" }
            assertTrue(text.contains(" Q")) { "G29 missing Q in $text" }
            assertTrue(text.contains(" E")) { "G29 missing E in $text" }
            assertTrue(text.contains(" D")) { "G29 missing D in $text" }
            assertTrue(text.contains(" J")) { "G29 missing J in $text" }
            assertTrue(text.contains(" V")) { "G29 missing V in $text" }
            assertEquals(it, BedLeveling3Point.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BedLevelingBilinear(a = true, c = true, o = true, q = true, x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), w = true, rate = BigDecimal("1.5"), e = true, d = true, linear = BigDecimal("1.5"), f = BigDecimal("1.5"), b = BigDecimal("1.5"), l = BigDecimal("1.5"), r = BigDecimal("1.5"), j = true, v = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "G29 missing A in $text" }
            assertTrue(text.contains(" C")) { "G29 missing C in $text" }
            assertTrue(text.contains(" O")) { "G29 missing O in $text" }
            assertTrue(text.contains(" Q")) { "G29 missing Q in $text" }
            assertTrue(text.contains(" X")) { "G29 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G29 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G29 missing Z in $text" }
            assertTrue(text.contains(" W")) { "G29 missing W in $text" }
            assertTrue(text.contains(" S")) { "G29 missing S in $text" }
            assertTrue(text.contains(" E")) { "G29 missing E in $text" }
            assertTrue(text.contains(" D")) { "G29 missing D in $text" }
            assertTrue(text.contains(" H")) { "G29 missing H in $text" }
            assertTrue(text.contains(" F")) { "G29 missing F in $text" }
            assertTrue(text.contains(" B")) { "G29 missing B in $text" }
            assertTrue(text.contains(" L")) { "G29 missing L in $text" }
            assertTrue(text.contains(" R")) { "G29 missing R in $text" }
            assertTrue(text.contains(" J")) { "G29 missing J in $text" }
            assertTrue(text.contains(" V")) { "G29 missing V in $text" }
            assertEquals(it, BedLevelingBilinear.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BedLevelingLinear(a = true, c = true, o = true, q = true, x = 1, y = 1, p = 1, rate = BigDecimal("1.5"), e = true, d = true, t = true, linear = BigDecimal("1.5"), f = BigDecimal("1.5"), b = BigDecimal("1.5"), l = BigDecimal("1.5"), r = BigDecimal("1.5"), j = true, v = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "G29 missing A in $text" }
            assertTrue(text.contains(" C")) { "G29 missing C in $text" }
            assertTrue(text.contains(" O")) { "G29 missing O in $text" }
            assertTrue(text.contains(" Q")) { "G29 missing Q in $text" }
            assertTrue(text.contains(" X")) { "G29 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G29 missing Y in $text" }
            assertTrue(text.contains(" P")) { "G29 missing P in $text" }
            assertTrue(text.contains(" S")) { "G29 missing S in $text" }
            assertTrue(text.contains(" E")) { "G29 missing E in $text" }
            assertTrue(text.contains(" D")) { "G29 missing D in $text" }
            assertTrue(text.contains(" T")) { "G29 missing T in $text" }
            assertTrue(text.contains(" H")) { "G29 missing H in $text" }
            assertTrue(text.contains(" F")) { "G29 missing F in $text" }
            assertTrue(text.contains(" B")) { "G29 missing B in $text" }
            assertTrue(text.contains(" L")) { "G29 missing L in $text" }
            assertTrue(text.contains(" R")) { "G29 missing R in $text" }
            assertTrue(text.contains(" J")) { "G29 missing J in $text" }
            assertTrue(text.contains(" V")) { "G29 missing V in $text" }
            assertEquals(it, BedLevelingLinear.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BedLevelingManual(s = 1, index = 1, j = 1, count = 1, y = 1, linear = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "G29 missing S in $text" }
            assertTrue(text.contains(" I")) { "G29 missing I in $text" }
            assertTrue(text.contains(" J")) { "G29 missing J in $text" }
            assertTrue(text.contains(" X")) { "G29 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G29 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G29 missing Z in $text" }
            assertEquals(it, BedLevelingManual.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BedLevelingUnified(a = true, b = BigDecimal("1.5"), c = BigDecimal("1.5"), d = true, e = true, f = BigDecimal("1.5"), h = BigDecimal("1.5"), i = 1, j = 1, k = 1, l = 1, p = 1, q = 1, r = 1, slot = 1, t = 1, u = true, v = 1, w = true, x = BigDecimal("1.5"), y = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "G29 missing A in $text" }
            assertTrue(text.contains(" B")) { "G29 missing B in $text" }
            assertTrue(text.contains(" C")) { "G29 missing C in $text" }
            assertTrue(text.contains(" D")) { "G29 missing D in $text" }
            assertTrue(text.contains(" E")) { "G29 missing E in $text" }
            assertTrue(text.contains(" F")) { "G29 missing F in $text" }
            assertTrue(text.contains(" H")) { "G29 missing H in $text" }
            assertTrue(text.contains(" I")) { "G29 missing I in $text" }
            assertTrue(text.contains(" J")) { "G29 missing J in $text" }
            assertTrue(text.contains(" K")) { "G29 missing K in $text" }
            assertTrue(text.contains(" L")) { "G29 missing L in $text" }
            assertTrue(text.contains(" P")) { "G29 missing P in $text" }
            assertTrue(text.contains(" Q")) { "G29 missing Q in $text" }
            assertTrue(text.contains(" R")) { "G29 missing R in $text" }
            assertTrue(text.contains(" S")) { "G29 missing S in $text" }
            assertTrue(text.contains(" T")) { "G29 missing T in $text" }
            assertTrue(text.contains(" U")) { "G29 missing U in $text" }
            assertTrue(text.contains(" V")) { "G29 missing V in $text" }
            assertTrue(text.contains(" W")) { "G29 missing W in $text" }
            assertTrue(text.contains(" X")) { "G29 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G29 missing Y in $text" }
            assertEquals(it, BedLevelingUnified.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SingleZProbe(c = true, pos = BigDecimal("1.5"), y = BigDecimal("1.5"), e = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "G30 missing C in $text" }
            assertTrue(text.contains(" X")) { "G30 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G30 missing Y in $text" }
            assertTrue(text.contains(" E")) { "G30 missing E in $text" }
            assertEquals(it, SingleZProbe.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DeltaAutoCalibration(c = BigDecimal("1.5"), e = true, f = 1, p = 1, t = true, v = 1, o = true, r = BigDecimal("1.5"), s = true, x = true, y = true, z = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "G33 missing C in $text" }
            assertTrue(text.contains(" E")) { "G33 missing E in $text" }
            assertTrue(text.contains(" F")) { "G33 missing F in $text" }
            assertTrue(text.contains(" P")) { "G33 missing P in $text" }
            assertTrue(text.contains(" T")) { "G33 missing T in $text" }
            assertTrue(text.contains(" V")) { "G33 missing V in $text" }
            assertTrue(text.contains(" O")) { "G33 missing O in $text" }
            assertTrue(text.contains(" R")) { "G33 missing R in $text" }
            assertTrue(text.contains(" S")) { "G33 missing S in $text" }
            assertTrue(text.contains(" X")) { "G33 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G33 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G33 missing Z in $text" }
            assertEquals(it, DeltaAutoCalibration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MechanicalGantryCalibration(s = 1, z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "G34 missing S in $text" }
            assertTrue(text.contains(" Z")) { "G34 missing Z in $text" }
            assertEquals(it, MechanicalGantryCalibration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ZSteppersAutoAlignment(l = true, z = 1, s = true, i = 1, t = BigDecimal("1.5"), a = BigDecimal("1.5"), e = true, r = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" L")) { "G34 missing L in $text" }
            assertTrue(text.contains(" Z")) { "G34 missing Z in $text" }
            assertTrue(text.contains(" S")) { "G34 missing S in $text" }
            assertTrue(text.contains(" I")) { "G34 missing I in $text" }
            assertTrue(text.contains(" T")) { "G34 missing T in $text" }
            assertTrue(text.contains(" A")) { "G34 missing A in $text" }
            assertTrue(text.contains(" E")) { "G34 missing E in $text" }
            assertTrue(text.contains(" R")) { "G34 missing R in $text" }
            assertEquals(it, ZSteppersAutoAlignment.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TrammingAssistant(s = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "G35 missing S in $text" }
            assertEquals(it, TrammingAssistant.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ProbeTargetG38_2(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), rate = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G38.2 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G38.2 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G38.2 missing Z in $text" }
            assertTrue(text.contains(" F")) { "G38.2 missing F in $text" }
            assertEquals(it, ProbeTargetG38_2.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ProbeTargetG38_3(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), rate = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G38.3 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G38.3 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G38.3 missing Z in $text" }
            assertTrue(text.contains(" F")) { "G38.3 missing F in $text" }
            assertEquals(it, ProbeTargetG38_3.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ProbeTargetG38_4(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), rate = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G38.4 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G38.4 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G38.4 missing Z in $text" }
            assertTrue(text.contains(" F")) { "G38.4 missing F in $text" }
            assertEquals(it, ProbeTargetG38_4.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ProbeTargetG38_5(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), rate = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G38.5 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G38.5 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G38.5 missing Z in $text" }
            assertTrue(text.contains(" F")) { "G38.5 missing F in $text" }
            assertEquals(it, ProbeTargetG38_5.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MoveToMeshCoordinate(pos = BigDecimal("1.5"), j = BigDecimal("1.5"), rate = BigDecimal("1.5"), p = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "G42 missing I in $text" }
            assertTrue(text.contains(" J")) { "G42 missing J in $text" }
            assertTrue(text.contains(" F")) { "G42 missing F in $text" }
            assertTrue(text.contains(" P")) { "G42 missing P in $text" }
            assertEquals(it, MoveToMeshCoordinate.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        StoredPositions(slot = 1, d = 1, q = 1, rate = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), e = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "G60 missing S in $text" }
            assertTrue(text.contains(" D")) { "G60 missing D in $text" }
            assertTrue(text.contains(" Q")) { "G60 missing Q in $text" }
            assertTrue(text.contains(" F")) { "G60 missing F in $text" }
            assertTrue(text.contains(" X")) { "G60 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G60 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G60 missing Z in $text" }
            assertTrue(text.contains(" E")) { "G60 missing E in $text" }
            assertEquals(it, StoredPositions.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ReturnToSavedPosition(rate = BigDecimal("1.5"), slot = 1, x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), e = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "G61 missing F in $text" }
            assertTrue(text.contains(" S")) { "G61 missing S in $text" }
            assertTrue(text.contains(" X")) { "G61 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G61 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G61 missing Z in $text" }
            assertTrue(text.contains(" E")) { "G61 missing E in $text" }
            assertEquals(it, ReturnToSavedPosition.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ProbeTemperatureCalibration(b = true, p = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" B")) { "G76 missing B in $text" }
            assertTrue(text.contains(" P")) { "G76 missing P in $text" }
            assertEquals(it, ProbeTemperatureCalibration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetPosition(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5"), e = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "G92 missing X in $text" }
            assertTrue(text.contains(" Y")) { "G92 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "G92 missing Z in $text" }
            assertTrue(text.contains(" A")) { "G92 missing A in $text" }
            assertTrue(text.contains(" B")) { "G92 missing B in $text" }
            assertTrue(text.contains(" C")) { "G92 missing C in $text" }
            assertTrue(text.contains(" U")) { "G92 missing U in $text" }
            assertTrue(text.contains(" V")) { "G92 missing V in $text" }
            assertTrue(text.contains(" W")) { "G92 missing W in $text" }
            assertTrue(text.contains(" E")) { "G92 missing E in $text" }
            assertEquals(it, SetPosition.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BacklashAndToolheadOffsetCalibration(b = true, index = 1, v = true, linear = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" B")) { "G425 missing B in $text" }
            assertTrue(text.contains(" T")) { "G425 missing T in $text" }
            assertTrue(text.contains(" V")) { "G425 missing V in $text" }
            assertTrue(text.contains(" U")) { "G425 missing U in $text" }
            assertEquals(it, BacklashAndToolheadOffsetCalibration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        UnconditionalStopM0(sec = 1, ms = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M0 missing S in $text" }
            assertTrue(text.contains(" P")) { "M0 missing P in $text" }
            assertEquals(it, UnconditionalStopM0.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        UnconditionalStopM1(sec = 1, ms = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M1 missing S in $text" }
            assertTrue(text.contains(" P")) { "M1 missing P in $text" }
            assertEquals(it, UnconditionalStopM1.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SpindleCWLaserOn(power = 1, o = 1, mode = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M3 missing S in $text" }
            assertTrue(text.contains(" O")) { "M3 missing O in $text" }
            assertTrue(text.contains(" I")) { "M3 missing I in $text" }
            assertEquals(it, SpindleCWLaserOn.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SpindleCCWLaserOn(power = 1, o = 1, mode = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M4 missing S in $text" }
            assertTrue(text.contains(" O")) { "M4 missing O in $text" }
            assertTrue(text.contains(" I")) { "M4 missing I in $text" }
            assertEquals(it, SpindleCCWLaserOn.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        EnableSteppers(x = true, y = true, z = true, e = true, a = true, b = true, c = true, u = true, v = true, w = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M17 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M17 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M17 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M17 missing E in $text" }
            assertTrue(text.contains(" A")) { "M17 missing A in $text" }
            assertTrue(text.contains(" B")) { "M17 missing B in $text" }
            assertTrue(text.contains(" C")) { "M17 missing C in $text" }
            assertTrue(text.contains(" U")) { "M17 missing U in $text" }
            assertTrue(text.contains(" V")) { "M17 missing V in $text" }
            assertTrue(text.contains(" W")) { "M17 missing W in $text" }
            assertEquals(it, EnableSteppers.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DisableSteppersM18(seconds = 1, x = true, y = true, z = true, e = true, a = true, b = true, c = true, u = true, v = true, w = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M18 missing S in $text" }
            assertTrue(text.contains(" X")) { "M18 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M18 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M18 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M18 missing E in $text" }
            assertTrue(text.contains(" A")) { "M18 missing A in $text" }
            assertTrue(text.contains(" B")) { "M18 missing B in $text" }
            assertTrue(text.contains(" C")) { "M18 missing C in $text" }
            assertTrue(text.contains(" U")) { "M18 missing U in $text" }
            assertTrue(text.contains(" V")) { "M18 missing V in $text" }
            assertTrue(text.contains(" W")) { "M18 missing W in $text" }
            assertEquals(it, DisableSteppersM18.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ListSDCard(f = true, l = true, t = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "M20 missing F in $text" }
            assertTrue(text.contains(" L")) { "M20 missing L in $text" }
            assertTrue(text.contains(" T")) { "M20 missing T in $text" }
            assertEquals(it, ListSDCard.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        StartOrResumeSDPrint(pos = 1L, time = 1L).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M24 missing S in $text" }
            assertTrue(text.contains(" T")) { "M24 missing T in $text" }
            assertEquals(it, StartOrResumeSDPrint.decodeParams(it.encode().params)) { "round trip: $text" }
        }
    }

    @Test
    fun `every parameter is written and read back 2`() {
        SetSDPosition(pos = 1L).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M26 missing S in $text" }
            assertEquals(it, SetSDPosition.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ReportSDPrintStatus(seconds = 1, c = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M27 missing S in $text" }
            assertTrue(text.contains(" C")) { "M27 missing C in $text" }
            assertEquals(it, ReportSDPrintStatus.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectAndStart(p = 1, filepos = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M32 missing P in $text" }
            assertTrue(text.contains(" S")) { "M32 missing S in $text" }
            assertEquals(it, SelectAndStart.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SDCardSorting(s = BigDecimal("1.5"), f = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M34 missing S in $text" }
            assertTrue(text.contains(" F")) { "M34 missing F in $text" }
            assertEquals(it, SDCardSorting.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetPinState(i = true, t = 1, pin = 1, state = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M42 missing I in $text" }
            assertTrue(text.contains(" T")) { "M42 missing T in $text" }
            assertTrue(text.contains(" P")) { "M42 missing P in $text" }
            assertTrue(text.contains(" S")) { "M42 missing S in $text" }
            assertEquals(it, SetPinState.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PinsDebugging(pin = 1, w = true, e = true, t = true, s = true, i = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M43 missing P in $text" }
            assertTrue(text.contains(" W")) { "M43 missing W in $text" }
            assertTrue(text.contains(" E")) { "M43 missing E in $text" }
            assertTrue(text.contains(" T")) { "M43 missing T in $text" }
            assertTrue(text.contains(" S")) { "M43 missing S in $text" }
            assertTrue(text.contains(" I")) { "M43 missing I in $text" }
            assertEquals(it, PinsDebugging.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ProbeRepeatabilityTest(c = true, engage = true, legs = 1, count = 1, s = 1, level = 1, pos = BigDecimal("1.5"), y = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "M48 missing C in $text" }
            assertTrue(text.contains(" E")) { "M48 missing E in $text" }
            assertTrue(text.contains(" L")) { "M48 missing L in $text" }
            assertTrue(text.contains(" P")) { "M48 missing P in $text" }
            assertTrue(text.contains(" S")) { "M48 missing S in $text" }
            assertTrue(text.contains(" V")) { "M48 missing V in $text" }
            assertTrue(text.contains(" X")) { "M48 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M48 missing Y in $text" }
            assertEquals(it, ProbeRepeatabilityTest.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetPrintProgress(minutes = 1, percent = 1, r = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "M73 missing C in $text" }
            assertTrue(text.contains(" P")) { "M73 missing P in $text" }
            assertTrue(text.contains(" R")) { "M73 missing R in $text" }
            assertEquals(it, SetPrintProgress.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PowerOn(s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M80 missing S in $text" }
            assertEquals(it, PowerOn.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DisableSteppersM84(seconds = 1, x = true, y = true, z = true, e = true, a = true, b = true, c = true, u = true, v = true, w = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M84 missing S in $text" }
            assertTrue(text.contains(" X")) { "M84 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M84 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M84 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M84 missing E in $text" }
            assertTrue(text.contains(" A")) { "M84 missing A in $text" }
            assertTrue(text.contains(" B")) { "M84 missing B in $text" }
            assertTrue(text.contains(" C")) { "M84 missing C in $text" }
            assertTrue(text.contains(" U")) { "M84 missing U in $text" }
            assertTrue(text.contains(" V")) { "M84 missing V in $text" }
            assertTrue(text.contains(" W")) { "M84 missing W in $text" }
            assertEquals(it, DisableSteppersM84.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        InactivityShutdown(seconds = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M85 missing S in $text" }
            assertEquals(it, InactivityShutdown.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        HotendIdleTimeout(seconds = 1, temp = 1, e = 1, b = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M86 missing S in $text" }
            assertTrue(text.contains(" T")) { "M86 missing T in $text" }
            assertTrue(text.contains(" E")) { "M86 missing E in $text" }
            assertTrue(text.contains(" B")) { "M86 missing B in $text" }
            assertEquals(it, HotendIdleTimeout.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetAxisStepsPerUnit(steps = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5"), e = BigDecimal("1.5"), index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M92 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M92 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M92 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M92 missing A in $text" }
            assertTrue(text.contains(" B")) { "M92 missing B in $text" }
            assertTrue(text.contains(" C")) { "M92 missing C in $text" }
            assertTrue(text.contains(" U")) { "M92 missing U in $text" }
            assertTrue(text.contains(" V")) { "M92 missing V in $text" }
            assertTrue(text.contains(" W")) { "M92 missing W in $text" }
            assertTrue(text.contains(" E")) { "M92 missing E in $text" }
            assertTrue(text.contains(" T")) { "M92 missing T in $text" }
            assertEquals(it, SetAxisStepsPerUnit.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FreeMemory(d = true, f = true, i = true, n = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" D")) { "M100 missing D in $text" }
            assertTrue(text.contains(" F")) { "M100 missing F in $text" }
            assertTrue(text.contains(" I")) { "M100 missing I in $text" }
            assertTrue(text.contains(" C")) { "M100 missing C in $text" }
            assertEquals(it, FreeMemory.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ConfigureBedDistanceSensor(s = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M102 missing S in $text" }
            assertEquals(it, ConfigureBedDistanceSensor.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetHotendTemperature(index = 1, temp = BigDecimal("1.5"), factor = BigDecimal("1.5"), b = BigDecimal("1.5"), t = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M104 missing I in $text" }
            assertTrue(text.contains(" S")) { "M104 missing S in $text" }
            assertTrue(text.contains(" F")) { "M104 missing F in $text" }
            assertTrue(text.contains(" B")) { "M104 missing B in $text" }
            assertTrue(text.contains(" T")) { "M104 missing T in $text" }
            assertEquals(it, SetHotendTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ReportHotendTemperature(r = true, index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" R")) { "M105 missing R in $text" }
            assertTrue(text.contains(" T")) { "M105 missing T in $text" }
            assertEquals(it, ReportHotendTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetFanSpeed(index = 1, speed = 1, p = 1, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M106 missing I in $text" }
            assertTrue(text.contains(" S")) { "M106 missing S in $text" }
            assertTrue(text.contains(" P")) { "M106 missing P in $text" }
            assertTrue(text.contains(" T")) { "M106 missing T in $text" }
            assertEquals(it, SetFanSpeed.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FanOff(index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M107 missing P in $text" }
            assertEquals(it, FanOff.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        WaitForHotendTemperature(index = 1, temp = BigDecimal("1.5"), r = BigDecimal("1.5"), factor = BigDecimal("1.5"), b = BigDecimal("1.5"), t = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M109 missing I in $text" }
            assertTrue(text.contains(" S")) { "M109 missing S in $text" }
            assertTrue(text.contains(" R")) { "M109 missing R in $text" }
            assertTrue(text.contains(" F")) { "M109 missing F in $text" }
            assertTrue(text.contains(" B")) { "M109 missing B in $text" }
            assertTrue(text.contains(" T")) { "M109 missing T in $text" }
            assertEquals(it, WaitForHotendTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetGetLineNumber(line = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" N")) { "M110 missing N in $text" }
            assertEquals(it, SetGetLineNumber.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DebugLevel(flags = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M111 missing S in $text" }
            assertEquals(it, DebugLevel.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        HostKeepalive(seconds = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M113 missing S in $text" }
            assertEquals(it, HostKeepalive.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        GetCurrentPosition(d = true, e = true, r = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" D")) { "M114 missing D in $text" }
            assertTrue(text.contains(" E")) { "M114 missing E in $text" }
            assertTrue(text.contains(" R")) { "M114 missing R in $text" }
            assertEquals(it, GetCurrentPosition.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SerialPrint(p = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M118 missing P in $text" }
            assertEquals(it, SerialPrint.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TMCDebugging(i = true, x = true, y = true, z = true, e = true, v = true, s = true, ms = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M122 missing I in $text" }
            assertTrue(text.contains(" X")) { "M122 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M122 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M122 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M122 missing E in $text" }
            assertTrue(text.contains(" V")) { "M122 missing V in $text" }
            assertTrue(text.contains(" S")) { "M122 missing S in $text" }
            assertTrue(text.contains(" P")) { "M122 missing P in $text" }
            assertEquals(it, TMCDebugging.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ParkHead(linear = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), p = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" L")) { "M125 missing L in $text" }
            assertTrue(text.contains(" X")) { "M125 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M125 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M125 missing Z in $text" }
            assertTrue(text.contains(" P")) { "M125 missing P in $text" }
            assertEquals(it, ParkHead.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        Baricuda1Open(pressure = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M126 missing S in $text" }
            assertEquals(it, Baricuda1Open.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        Baricuda2Open(pressure = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M128 missing S in $text" }
            assertEquals(it, Baricuda2Open.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetBedTemperature(index = 1, temp = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M140 missing I in $text" }
            assertTrue(text.contains(" S")) { "M140 missing S in $text" }
            assertEquals(it, SetBedTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetChamberTemperature(temp = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M141 missing S in $text" }
            assertEquals(it, SetChamberTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetLaserCoolerTemperature(temp = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M143 missing S in $text" }
            assertEquals(it, SetLaserCoolerTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetMaterialPreset(index = 1, temp = 1, b = 1, speed = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M145 missing S in $text" }
            assertTrue(text.contains(" H")) { "M145 missing H in $text" }
            assertTrue(text.contains(" B")) { "M145 missing B in $text" }
            assertTrue(text.contains(" F")) { "M145 missing F in $text" }
            assertEquals(it, SetMaterialPreset.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetTemperatureUnits(c = true, f = true, k = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "M149 missing C in $text" }
            assertTrue(text.contains(" F")) { "M149 missing F in $text" }
            assertTrue(text.contains(" K")) { "M149 missing K in $text" }
            assertEquals(it, SetTemperatureUnits.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetRGBWColor(intensity = 1, u = 1, b = 1, w = 1, p = 1, pixel = 1, strip = 1, k = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" R")) { "M150 missing R in $text" }
            assertTrue(text.contains(" U")) { "M150 missing U in $text" }
            assertTrue(text.contains(" B")) { "M150 missing B in $text" }
            assertTrue(text.contains(" W")) { "M150 missing W in $text" }
            assertTrue(text.contains(" P")) { "M150 missing P in $text" }
            assertTrue(text.contains(" I")) { "M150 missing I in $text" }
            assertTrue(text.contains(" S")) { "M150 missing S in $text" }
            assertTrue(text.contains(" K")) { "M150 missing K in $text" }
            assertEquals(it, SetRGBWColor.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PositionAutoReport(seconds = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M154 missing S in $text" }
            assertEquals(it, PositionAutoReport.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TemperatureAutoReport(seconds = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M155 missing S in $text" }
            assertEquals(it, TemperatureAutoReport.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetMixFactor(index = 1, factor = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M163 missing S in $text" }
            assertTrue(text.contains(" P")) { "M163 missing P in $text" }
            assertEquals(it, SetMixFactor.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SaveMix(index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M164 missing S in $text" }
            assertEquals(it, SaveMix.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetMix(factor = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), d = BigDecimal("1.5"), h = BigDecimal("1.5"), i = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "M165 missing A in $text" }
            assertTrue(text.contains(" B")) { "M165 missing B in $text" }
            assertTrue(text.contains(" C")) { "M165 missing C in $text" }
            assertTrue(text.contains(" D")) { "M165 missing D in $text" }
            assertTrue(text.contains(" H")) { "M165 missing H in $text" }
            assertTrue(text.contains(" I")) { "M165 missing I in $text" }
            assertEquals(it, SetMix.decodeParams(it.encode().params)) { "round trip: $text" }
        }
    }

    @Test
    fun `every parameter is written and read back 3`() {
        GradientMix(linear = BigDecimal("1.5"), z = BigDecimal("1.5"), index = 1, j = 1, enable = true, t = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "M166 missing A in $text" }
            assertTrue(text.contains(" Z")) { "M166 missing Z in $text" }
            assertTrue(text.contains(" I")) { "M166 missing I in $text" }
            assertTrue(text.contains(" J")) { "M166 missing J in $text" }
            assertTrue(text.contains(" S")) { "M166 missing S in $text" }
            assertTrue(text.contains(" T")) { "M166 missing T in $text" }
            assertEquals(it, GradientMix.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        WaitForBedTemperature(index = 1, temp = BigDecimal("1.5"), r = BigDecimal("1.5"), seconds = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M190 missing I in $text" }
            assertTrue(text.contains(" S")) { "M190 missing S in $text" }
            assertTrue(text.contains(" R")) { "M190 missing R in $text" }
            assertTrue(text.contains(" T")) { "M190 missing T in $text" }
            assertEquals(it, WaitForBedTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        WaitForChamberTemperature(temp = BigDecimal("1.5"), r = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M191 missing S in $text" }
            assertTrue(text.contains(" R")) { "M191 missing R in $text" }
            assertEquals(it, WaitForChamberTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        WaitForProbeTemperature(temp = 1, s = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" R")) { "M192 missing R in $text" }
            assertTrue(text.contains(" S")) { "M192 missing S in $text" }
            assertEquals(it, WaitForProbeTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        WaitForLaserCoolerTemperature(temp = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M193 missing S in $text" }
            assertEquals(it, WaitForLaserCoolerTemperature.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        VolumetricExtrusionDiameter(diameter = BigDecimal("1.5"), volume = BigDecimal("1.5"), s = 1, index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" D")) { "M200 missing D in $text" }
            assertTrue(text.contains(" L")) { "M200 missing L in $text" }
            assertTrue(text.contains(" S")) { "M200 missing S in $text" }
            assertTrue(text.contains(" T")) { "M200 missing T in $text" }
            assertEquals(it, VolumetricExtrusionDiameter.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PrintTravelMoveLimits(accel = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), e = BigDecimal("1.5"), index = 1, f = 1, percent = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M201 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M201 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M201 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M201 missing E in $text" }
            assertTrue(text.contains(" T")) { "M201 missing T in $text" }
            assertTrue(text.contains(" F")) { "M201 missing F in $text" }
            assertTrue(text.contains(" S")) { "M201 missing S in $text" }
            assertEquals(it, PrintTravelMoveLimits.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetMaxFeedrate(x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), e = BigDecimal("1.5"), index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M203 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M203 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M203 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M203 missing E in $text" }
            assertTrue(text.contains(" T")) { "M203 missing T in $text" }
            assertEquals(it, SetMaxFeedrate.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetStartingAcceleration(accel = BigDecimal("1.5"), r = BigDecimal("1.5"), t = BigDecimal("1.5"), s = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M204 missing P in $text" }
            assertTrue(text.contains(" R")) { "M204 missing R in $text" }
            assertTrue(text.contains(" T")) { "M204 missing T in $text" }
            assertTrue(text.contains(" S")) { "M204 missing S in $text" }
            assertEquals(it, SetStartingAcceleration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetAdvancedSettings(jerk = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), e = BigDecimal("1.5"), b = 1, s = BigDecimal("1.5"), t = BigDecimal("1.5"), deviation = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M205 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M205 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M205 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M205 missing E in $text" }
            assertTrue(text.contains(" B")) { "M205 missing B in $text" }
            assertTrue(text.contains(" S")) { "M205 missing S in $text" }
            assertTrue(text.contains(" T")) { "M205 missing T in $text" }
            assertTrue(text.contains(" J")) { "M205 missing J in $text" }
            assertEquals(it, SetAdvancedSettings.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetHomeOffsets(offset = BigDecimal("1.5"), t = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M206 missing P in $text" }
            assertTrue(text.contains(" T")) { "M206 missing T in $text" }
            assertTrue(text.contains(" X")) { "M206 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M206 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M206 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M206 missing A in $text" }
            assertTrue(text.contains(" B")) { "M206 missing B in $text" }
            assertTrue(text.contains(" C")) { "M206 missing C in $text" }
            assertTrue(text.contains(" U")) { "M206 missing U in $text" }
            assertTrue(text.contains(" V")) { "M206 missing V in $text" }
            assertTrue(text.contains(" W")) { "M206 missing W in $text" }
            assertEquals(it, SetHomeOffsets.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FirmwareRetractionSettings(length = BigDecimal("1.5"), w = BigDecimal("1.5"), feedrate = BigDecimal("1.5"), z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M207 missing S in $text" }
            assertTrue(text.contains(" W")) { "M207 missing W in $text" }
            assertTrue(text.contains(" F")) { "M207 missing F in $text" }
            assertTrue(text.contains(" Z")) { "M207 missing Z in $text" }
            assertEquals(it, FirmwareRetractionSettings.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FirmwareRecoverSettings(length = BigDecimal("1.5"), w = BigDecimal("1.5"), feedrate = BigDecimal("1.5"), r = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M208 missing S in $text" }
            assertTrue(text.contains(" W")) { "M208 missing W in $text" }
            assertTrue(text.contains(" F")) { "M208 missing F in $text" }
            assertTrue(text.contains(" R")) { "M208 missing R in $text" }
            assertEquals(it, FirmwareRecoverSettings.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetAutoRetract(s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M209 missing S in $text" }
            assertEquals(it, SetAutoRetract.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        HomingFeedrate(feedrate = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M210 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M210 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M210 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M210 missing A in $text" }
            assertTrue(text.contains(" B")) { "M210 missing B in $text" }
            assertTrue(text.contains(" C")) { "M210 missing C in $text" }
            assertTrue(text.contains(" U")) { "M210 missing U in $text" }
            assertTrue(text.contains(" V")) { "M210 missing V in $text" }
            assertTrue(text.contains(" W")) { "M210 missing W in $text" }
            assertEquals(it, HomingFeedrate.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SoftwareEndstops(s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M211 missing S in $text" }
            assertEquals(it, SoftwareEndstops.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FilamentSwapParameters(q = true, linear = BigDecimal("1.5"), b = BigDecimal("1.5"), e = BigDecimal("1.5"), feedrate = 1, r = 1, u = 1, f = 1, g = 1, a = 1, l = 1, w = 1, x = BigDecimal("1.5"), y = BigDecimal("1.5"), v = 1, z = 1, i = BigDecimal("1.5"), j = BigDecimal("1.5"), k = BigDecimal("1.5"), c = BigDecimal("1.5"), h = BigDecimal("1.5"), o = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" Q")) { "M217 missing Q in $text" }
            assertTrue(text.contains(" S")) { "M217 missing S in $text" }
            assertTrue(text.contains(" B")) { "M217 missing B in $text" }
            assertTrue(text.contains(" E")) { "M217 missing E in $text" }
            assertTrue(text.contains(" P")) { "M217 missing P in $text" }
            assertTrue(text.contains(" R")) { "M217 missing R in $text" }
            assertTrue(text.contains(" U")) { "M217 missing U in $text" }
            assertTrue(text.contains(" F")) { "M217 missing F in $text" }
            assertTrue(text.contains(" G")) { "M217 missing G in $text" }
            assertTrue(text.contains(" A")) { "M217 missing A in $text" }
            assertTrue(text.contains(" L")) { "M217 missing L in $text" }
            assertTrue(text.contains(" W")) { "M217 missing W in $text" }
            assertTrue(text.contains(" X")) { "M217 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M217 missing Y in $text" }
            assertTrue(text.contains(" V")) { "M217 missing V in $text" }
            assertTrue(text.contains(" Z")) { "M217 missing Z in $text" }
            assertTrue(text.contains(" I")) { "M217 missing I in $text" }
            assertTrue(text.contains(" J")) { "M217 missing J in $text" }
            assertTrue(text.contains(" K")) { "M217 missing K in $text" }
            assertTrue(text.contains(" C")) { "M217 missing C in $text" }
            assertTrue(text.contains(" H")) { "M217 missing H in $text" }
            assertTrue(text.contains(" O")) { "M217 missing O in $text" }
            assertEquals(it, FilamentSwapParameters.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetHotendOffset(index = 1, offset = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" T")) { "M218 missing T in $text" }
            assertTrue(text.contains(" X")) { "M218 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M218 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M218 missing Z in $text" }
            assertEquals(it, SetHotendOffset.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetFeedratePercentage(percent = 1, b = true, r = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M220 missing S in $text" }
            assertTrue(text.contains(" B")) { "M220 missing B in $text" }
            assertTrue(text.contains(" R")) { "M220 missing R in $text" }
            assertEquals(it, SetFeedratePercentage.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetFlowPercentage(percent = 1, index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M221 missing S in $text" }
            assertTrue(text.contains(" T")) { "M221 missing T in $text" }
            assertEquals(it, SetFlowPercentage.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        WaitForPinState(pin = 1, state = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M226 missing P in $text" }
            assertTrue(text.contains(" S")) { "M226 missing S in $text" }
            assertEquals(it, WaitForPinState.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TriggerCamera(offset = BigDecimal("1.5"), b = BigDecimal("1.5"), ms = 1, feedrate = BigDecimal("1.5"), pos = BigDecimal("1.5"), j = BigDecimal("1.5"), p = 1, length = BigDecimal("1.5"), s = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "M240 missing A in $text" }
            assertTrue(text.contains(" B")) { "M240 missing B in $text" }
            assertTrue(text.contains(" D")) { "M240 missing D in $text" }
            assertTrue(text.contains(" F")) { "M240 missing F in $text" }
            assertTrue(text.contains(" I")) { "M240 missing I in $text" }
            assertTrue(text.contains(" J")) { "M240 missing J in $text" }
            assertTrue(text.contains(" P")) { "M240 missing P in $text" }
            assertTrue(text.contains(" R")) { "M240 missing R in $text" }
            assertTrue(text.contains(" S")) { "M240 missing S in $text" }
            assertTrue(text.contains(" X")) { "M240 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M240 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M240 missing Z in $text" }
            assertEquals(it, TriggerCamera.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        LCDContrast(contrast = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "M250 missing C in $text" }
            assertEquals(it, LCDContrast.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        LCDSleepBacklightTimeout(minutes = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M255 missing S in $text" }
            assertEquals(it, LCDSleepBacklightTimeout.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        LCDBrightness(brightness = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" B")) { "M256 missing B in $text" }
            assertEquals(it, LCDBrightness.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CSend(addr = 1, byte = 1, r = true, s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "M260 missing A in $text" }
            assertTrue(text.contains(" B")) { "M260 missing B in $text" }
            assertTrue(text.contains(" R")) { "M260 missing R in $text" }
            assertTrue(text.contains(" S")) { "M260 missing S in $text" }
            assertEquals(it, I2CSend.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CRequest(addr = 1, count = 1, s = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "M261 missing A in $text" }
            assertTrue(text.contains(" B")) { "M261 missing B in $text" }
            assertTrue(text.contains(" S")) { "M261 missing S in $text" }
            assertEquals(it, I2CRequest.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ServoPosition(index = 1, pos = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M280 missing P in $text" }
            assertTrue(text.contains(" S")) { "M280 missing S in $text" }
            assertEquals(it, ServoPosition.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        EditServoAngles(index = 1, degrees = 1, u = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M281 missing P in $text" }
            assertTrue(text.contains(" L")) { "M281 missing L in $text" }
            assertTrue(text.contains(" U")) { "M281 missing U in $text" }
            assertEquals(it, EditServoAngles.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DetachServo(index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M282 missing P in $text" }
            assertEquals(it, DetachServo.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        Babystep(pos = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), s = BigDecimal("1.5"), p = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M290 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M290 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M290 missing Z in $text" }
            assertTrue(text.contains(" S")) { "M290 missing S in $text" }
            assertTrue(text.contains(" P")) { "M290 missing P in $text" }
            assertEquals(it, Babystep.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PlayTone(ms = 1, s = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M300 missing P in $text" }
            assertTrue(text.contains(" S")) { "M300 missing S in $text" }
            assertEquals(it, PlayTone.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetHotendPID(index = 1, value = BigDecimal("1.5"), i = BigDecimal("1.5"), d = BigDecimal("1.5"), c = BigDecimal("1.5"), l = BigDecimal("1.5"), f = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" E")) { "M301 missing E in $text" }
            assertTrue(text.contains(" P")) { "M301 missing P in $text" }
            assertTrue(text.contains(" I")) { "M301 missing I in $text" }
            assertTrue(text.contains(" D")) { "M301 missing D in $text" }
            assertTrue(text.contains(" C")) { "M301 missing C in $text" }
            assertTrue(text.contains(" L")) { "M301 missing L in $text" }
            assertTrue(text.contains(" F")) { "M301 missing F in $text" }
            assertEquals(it, SetHotendPID.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ColdExtrude(temp = BigDecimal("1.5"), p = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M302 missing S in $text" }
            assertTrue(text.contains(" P")) { "M302 missing P in $text" }
            assertEquals(it, ColdExtrude.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PIDAutotune(index = 1, count = 1, temp = BigDecimal("1.5"), u = true, d = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" E")) { "M303 missing E in $text" }
            assertTrue(text.contains(" C")) { "M303 missing C in $text" }
            assertTrue(text.contains(" S")) { "M303 missing S in $text" }
            assertTrue(text.contains(" U")) { "M303 missing U in $text" }
            assertTrue(text.contains(" D")) { "M303 missing D in $text" }
            assertEquals(it, PIDAutotune.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetBedPID(value = BigDecimal("1.5"), i = BigDecimal("1.5"), d = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M304 missing P in $text" }
            assertTrue(text.contains(" I")) { "M304 missing I in $text" }
            assertTrue(text.contains(" D")) { "M304 missing D in $text" }
            assertEquals(it, SetBedPID.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        UserThermistorParameters(index = 1, ohm = 1, ohms = 1, beta = 1, coeff = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M305 missing P in $text" }
            assertTrue(text.contains(" R")) { "M305 missing R in $text" }
            assertTrue(text.contains(" T")) { "M305 missing T in $text" }
            assertTrue(text.contains(" B")) { "M305 missing B in $text" }
            assertTrue(text.contains(" C")) { "M305 missing C in $text" }
            assertEquals(it, UserThermistorParameters.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ModelPredictiveTempControl(value = BigDecimal("1.5"), c = BigDecimal("1.5"), index = 1, f = BigDecimal("1.5"), h = BigDecimal("1.5"), p = BigDecimal("1.5"), r = BigDecimal("1.5"), s = 1, t = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "M306 missing A in $text" }
            assertTrue(text.contains(" C")) { "M306 missing C in $text" }
            assertTrue(text.contains(" E")) { "M306 missing E in $text" }
            assertTrue(text.contains(" F")) { "M306 missing F in $text" }
            assertTrue(text.contains(" H")) { "M306 missing H in $text" }
            assertTrue(text.contains(" P")) { "M306 missing P in $text" }
            assertTrue(text.contains(" R")) { "M306 missing R in $text" }
            assertTrue(text.contains(" S")) { "M306 missing S in $text" }
            assertTrue(text.contains(" T")) { "M306 missing T in $text" }
            assertEquals(it, ModelPredictiveTempControl.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetChamberPID(value = BigDecimal("1.5"), i = BigDecimal("1.5"), d = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M309 missing P in $text" }
            assertTrue(text.contains(" I")) { "M309 missing I in $text" }
            assertTrue(text.contains(" D")) { "M309 missing D in $text" }
            assertEquals(it, SetChamberPID.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetMicroStepping(b = 1, s = 1, x = 1, y = 1, z = 1, a = 1, c = 1, u = 1, v = 1, w = 1, e = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" B")) { "M350 missing B in $text" }
            assertTrue(text.contains(" S")) { "M350 missing S in $text" }
            assertTrue(text.contains(" X")) { "M350 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M350 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M350 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M350 missing A in $text" }
            assertTrue(text.contains(" C")) { "M350 missing C in $text" }
            assertTrue(text.contains(" U")) { "M350 missing U in $text" }
            assertTrue(text.contains(" V")) { "M350 missing V in $text" }
            assertTrue(text.contains(" W")) { "M350 missing W in $text" }
            assertTrue(text.contains(" E")) { "M350 missing E in $text" }
            assertEquals(it, SetMicroStepping.decodeParams(it.encode().params)) { "round trip: $text" }
        }
    }

    @Test
    fun `every parameter is written and read back 4`() {
        SetMicrostepPins(s = 1, b = 1, x = 1, y = 1, z = 1, e = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M351 missing S in $text" }
            assertTrue(text.contains(" B")) { "M351 missing B in $text" }
            assertTrue(text.contains(" X")) { "M351 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M351 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M351 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M351 missing E in $text" }
            assertEquals(it, SetMicrostepPins.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        CaseLightControl(p = 1, s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M355 missing P in $text" }
            assertTrue(text.contains(" S")) { "M355 missing S in $text" }
            assertEquals(it, CaseLightControl.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ActivateSolenoid(index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M380 missing S in $text" }
            assertEquals(it, ActivateSolenoid.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DeactivateSolenoids(index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M381 missing S in $text" }
            assertEquals(it, DeactivateSolenoids.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DeployProbe(h = true, s = true, r = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" H")) { "M401 missing H in $text" }
            assertTrue(text.contains(" S")) { "M401 missing S in $text" }
            assertTrue(text.contains(" R")) { "M401 missing R in $text" }
            assertEquals(it, DeployProbe.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        StowProbe(r = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" R")) { "M402 missing R in $text" }
            assertEquals(it, StowProbe.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MMU2FilamentType(index = 1, f = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" E")) { "M403 missing E in $text" }
            assertTrue(text.contains(" F")) { "M403 missing F in $text" }
            assertEquals(it, MMU2FilamentType.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FilamentWidthSensorNominalDiameter(linear = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" W")) { "M404 missing W in $text" }
            assertEquals(it, FilamentWidthSensorNominalDiameter.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FilamentWidthSensorOn(d = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" D")) { "M405 missing D in $text" }
            assertEquals(it, FilamentWidthSensorOn.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FilamentRunout(linear = BigDecimal("1.5"), h = true, l = BigDecimal("1.5"), s = true, r = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" D")) { "M412 missing D in $text" }
            assertTrue(text.contains(" H")) { "M412 missing H in $text" }
            assertTrue(text.contains(" L")) { "M412 missing L in $text" }
            assertTrue(text.contains(" S")) { "M412 missing S in $text" }
            assertTrue(text.contains(" R")) { "M412 missing R in $text" }
            assertEquals(it, FilamentRunout.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PowerLossRecovery(s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M413 missing S in $text" }
            assertEquals(it, PowerLossRecovery.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        LCDLanguage(languageIndex = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M414 missing S in $text" }
            assertEquals(it, LCDLanguage.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BedLevelingState(l = 1, s = true, v = true, t = 1, linear = BigDecimal("1.5"), negativeOffset = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" L")) { "M420 missing L in $text" }
            assertTrue(text.contains(" S")) { "M420 missing S in $text" }
            assertTrue(text.contains(" V")) { "M420 missing V in $text" }
            assertTrue(text.contains(" T")) { "M420 missing T in $text" }
            assertTrue(text.contains(" Z")) { "M420 missing Z in $text" }
            assertTrue(text.contains(" C")) { "M420 missing C in $text" }
            assertEquals(it, BedLevelingState.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetMeshValue(index = 1, j = 1, linear = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), q = BigDecimal("1.5"), c = true, n = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M421 missing I in $text" }
            assertTrue(text.contains(" J")) { "M421 missing J in $text" }
            assertTrue(text.contains(" X")) { "M421 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M421 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M421 missing Z in $text" }
            assertTrue(text.contains(" Q")) { "M421 missing Q in $text" }
            assertTrue(text.contains(" C")) { "M421 missing C in $text" }
            assertTrue(text.contains(" N")) { "M421 missing N in $text" }
            assertEquals(it, SetMeshValue.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetZMotorXY(r = true, index = 1, w = 1, linear = BigDecimal("1.5"), y = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" R")) { "M422 missing R in $text" }
            assertTrue(text.contains(" S")) { "M422 missing S in $text" }
            assertTrue(text.contains(" W")) { "M422 missing W in $text" }
            assertTrue(text.contains(" X")) { "M422 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M422 missing Y in $text" }
            assertEquals(it, SetZMotorXY.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        XTwistCompensation(r = true, linear = BigDecimal("1.5"), e = BigDecimal("1.5"), i = BigDecimal("1.5"), index = 1, z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" R")) { "M423 missing R in $text" }
            assertTrue(text.contains(" A")) { "M423 missing A in $text" }
            assertTrue(text.contains(" E")) { "M423 missing E in $text" }
            assertTrue(text.contains(" I")) { "M423 missing I in $text" }
            assertTrue(text.contains(" X")) { "M423 missing X in $text" }
            assertTrue(text.contains(" Z")) { "M423 missing Z in $text" }
            assertEquals(it, XTwistCompensation.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BacklashCompensation(value = BigDecimal("1.5"), linear = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "M425 missing F in $text" }
            assertTrue(text.contains(" S")) { "M425 missing S in $text" }
            assertTrue(text.contains(" X")) { "M425 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M425 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M425 missing Z in $text" }
            assertEquals(it, BacklashCompensation.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        PowerMonitor(i = true, v = true, w = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M430 missing I in $text" }
            assertTrue(text.contains(" V")) { "M430 missing V in $text" }
            assertTrue(text.contains(" W")) { "M430 missing W in $text" }
            assertEquals(it, PowerMonitor.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        CancelObjects(c = true, index = 1, s = 1, count = 1, u = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "M486 missing C in $text" }
            assertTrue(text.contains(" P")) { "M486 missing P in $text" }
            assertTrue(text.contains(" S")) { "M486 missing S in $text" }
            assertTrue(text.contains(" T")) { "M486 missing T in $text" }
            assertTrue(text.contains(" U")) { "M486 missing U in $text" }
            assertEquals(it, CancelObjects.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FixedTimeMotion(s = 1, h = 1, c = 1, d = 1, a = BigDecimal("1.5"), scale = BigDecimal("1.5"), zeta = BigDecimal("1.5"), vtol = BigDecimal("1.5"), x = true, y = true, z = true, e = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M493 missing S in $text" }
            assertTrue(text.contains(" H")) { "M493 missing H in $text" }
            assertTrue(text.contains(" C")) { "M493 missing C in $text" }
            assertTrue(text.contains(" D")) { "M493 missing D in $text" }
            assertTrue(text.contains(" A")) { "M493 missing A in $text" }
            assertTrue(text.contains(" F")) { "M493 missing F in $text" }
            assertTrue(text.contains(" I")) { "M493 missing I in $text" }
            assertTrue(text.contains(" Q")) { "M493 missing Q in $text" }
            assertTrue(text.contains(" X")) { "M493 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M493 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M493 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M493 missing E in $text" }
            assertEquals(it, FixedTimeMotion.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FTMotionTrajectorySmoothing(t = true, o = true, x = true, y = true, z = true, e = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" T")) { "M494 missing T in $text" }
            assertTrue(text.contains(" O")) { "M494 missing O in $text" }
            assertTrue(text.contains(" X")) { "M494 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M494 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M494 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M494 missing E in $text" }
            assertEquals(it, FTMotionTrajectorySmoothing.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ReportSettings(s = true, c = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M503 missing S in $text" }
            assertTrue(text.contains(" C")) { "M503 missing C in $text" }
            assertEquals(it, ReportSettings.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        UnlockMachine(passcode = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M511 missing P in $text" }
            assertEquals(it, UnlockMachine.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetPasscode(password = 1, s = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M512 missing P in $text" }
            assertTrue(text.contains(" S")) { "M512 missing S in $text" }
            assertEquals(it, SetPasscode.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        EndstopsAbortSD(flag = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M540 missing S in $text" }
            assertEquals(it, EndstopsAbortSD.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MachineName(name = "x").let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M550 missing P in $text" }
            assertEquals(it, MachineName.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        EthernetIPAddressNetworkIF(ipAddress = "x", s = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M552 missing P in $text" }
            assertTrue(text.contains(" S")) { "M552 missing S in $text" }
            assertEquals(it, EthernetIPAddressNetworkIF.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        EthernetSubnetMask(subnetMask = "x").let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M553 missing P in $text" }
            assertEquals(it, EthernetSubnetMask.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        EthernetGatewayIPAddress(gateway = "x").let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M554 missing P in $text" }
            assertEquals(it, EthernetGatewayIPAddress.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetTMCSteppingMode(x = true, y = true, z = true, e = true, i = 1, t = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M569 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M569 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M569 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M569 missing E in $text" }
            assertTrue(text.contains(" I")) { "M569 missing I in $text" }
            assertTrue(text.contains(" T")) { "M569 missing T in $text" }
            assertEquals(it, SetTMCSteppingMode.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SerialBaudRate(p = true, baud = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M575 missing P in $text" }
            assertTrue(text.contains(" B")) { "M575 missing B in $text" }
            assertEquals(it, SerialBaudRate.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        NonlinearExtrusionControl(coeff = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" A")) { "M592 missing A in $text" }
            assertTrue(text.contains(" B")) { "M592 missing B in $text" }
            assertTrue(text.contains(" C")) { "M592 missing C in $text" }
            assertTrue(text.contains(" S")) { "M592 missing S in $text" }
            assertEquals(it, NonlinearExtrusionControl.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ZVInputShaping(zeta = BigDecimal("1.5"), hertz = BigDecimal("1.5"), x = true, y = true, z = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" D")) { "M593 missing D in $text" }
            assertTrue(text.contains(" F")) { "M593 missing F in $text" }
            assertTrue(text.contains(" X")) { "M593 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M593 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M593 missing Z in $text" }
            assertEquals(it, ZVInputShaping.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        FilamentChange(index = 1, pos = BigDecimal("1.5"), u = BigDecimal("1.5"), l = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), beeps = 1, temp = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" T")) { "M600 missing T in $text" }
            assertTrue(text.contains(" E")) { "M600 missing E in $text" }
            assertTrue(text.contains(" U")) { "M600 missing U in $text" }
            assertTrue(text.contains(" L")) { "M600 missing L in $text" }
            assertTrue(text.contains(" X")) { "M600 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M600 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M600 missing Z in $text" }
            assertTrue(text.contains(" B")) { "M600 missing B in $text" }
            assertTrue(text.contains(" R")) { "M600 missing R in $text" }
            assertEquals(it, FilamentChange.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ConfigureFilamentChange(index = 1, pos = BigDecimal("1.5"), l = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" T")) { "M603 missing T in $text" }
            assertTrue(text.contains(" U")) { "M603 missing U in $text" }
            assertTrue(text.contains(" L")) { "M603 missing L in $text" }
            assertEquals(it, ConfigureFilamentChange.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MultiNozzleMode(s = 1, x = BigDecimal("1.5"), r = 1, p = 1, e = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M605 missing S in $text" }
            assertTrue(text.contains(" X")) { "M605 missing X in $text" }
            assertTrue(text.contains(" R")) { "M605 missing R in $text" }
            assertTrue(text.contains(" P")) { "M605 missing P in $text" }
            assertTrue(text.contains(" E")) { "M605 missing E in $text" }
            assertEquals(it, MultiNozzleMode.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SCARAConfiguration(segmentsPerSecond = BigDecimal("1.5"), thetaPiOffset = BigDecimal("1.5"), thetaOffset = BigDecimal("1.5"), a = BigDecimal("1.5"), x = BigDecimal("1.5"), b = BigDecimal("1.5"), y = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M665 missing S in $text" }
            assertTrue(text.contains(" P")) { "M665 missing P in $text" }
            assertTrue(text.contains(" T")) { "M665 missing T in $text" }
            assertTrue(text.contains(" A")) { "M665 missing A in $text" }
            assertTrue(text.contains(" X")) { "M665 missing X in $text" }
            assertTrue(text.contains(" B")) { "M665 missing B in $text" }
            assertTrue(text.contains(" Y")) { "M665 missing Y in $text" }
            assertEquals(it, SCARAConfiguration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DeltaConfiguration(linear = BigDecimal("1.5"), l = BigDecimal("1.5"), r = BigDecimal("1.5"), s = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" H")) { "M665 missing H in $text" }
            assertTrue(text.contains(" L")) { "M665 missing L in $text" }
            assertTrue(text.contains(" R")) { "M665 missing R in $text" }
            assertTrue(text.contains(" S")) { "M665 missing S in $text" }
            assertTrue(text.contains(" X")) { "M665 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M665 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M665 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M665 missing A in $text" }
            assertTrue(text.contains(" B")) { "M665 missing B in $text" }
            assertTrue(text.contains(" C")) { "M665 missing C in $text" }
            assertEquals(it, DeltaConfiguration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        DualEndstopOffsets(adj = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M666 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M666 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M666 missing Z in $text" }
            assertEquals(it, DualEndstopOffsets.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetDeltaEndstopAdjustments(adj = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M666 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M666 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M666 missing Z in $text" }
            assertEquals(it, SetDeltaEndstopAdjustments.decodeParams(it.encode().params)) { "round trip: $text" }
        }
    }

    @Test
    fun `every parameter is written and read back 5`() {
        LoadFilament(extruder = 1, distance = BigDecimal("1.5"), l = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" T")) { "M701 missing T in $text" }
            assertTrue(text.contains(" Z")) { "M701 missing Z in $text" }
            assertTrue(text.contains(" L")) { "M701 missing L in $text" }
            assertEquals(it, LoadFilament.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        UnloadFilament(extruder = 1, distance = BigDecimal("1.5"), u = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" T")) { "M702 missing T in $text" }
            assertTrue(text.contains(" Z")) { "M702 missing Z in $text" }
            assertTrue(text.contains(" U")) { "M702 missing U in $text" }
            assertEquals(it, UnloadFilament.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ControllerFanSettings(speed = 1, i = 1, a = true, r = true, seconds = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M710 missing S in $text" }
            assertTrue(text.contains(" I")) { "M710 missing I in $text" }
            assertTrue(text.contains(" A")) { "M710 missing A in $text" }
            assertTrue(text.contains(" R")) { "M710 missing R in $text" }
            assertTrue(text.contains(" D")) { "M710 missing D in $text" }
            assertEquals(it, ControllerFanSettings.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        RepeatMarker(l = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" L")) { "M808 missing L in $text" }
            assertEquals(it, RepeatMarker.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        XYZProbeOffset(x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" X")) { "M851 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M851 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M851 missing Z in $text" }
            assertEquals(it, XYZProbeOffset.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        BedSkewCompensation(i = true, j = true, k = true, s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M852 missing I in $text" }
            assertTrue(text.contains(" J")) { "M852 missing J in $text" }
            assertTrue(text.contains(" K")) { "M852 missing K in $text" }
            assertTrue(text.contains(" S")) { "M852 missing S in $text" }
            assertEquals(it, BedSkewCompensation.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM860(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M860 missing I in $text" }
            assertTrue(text.contains(" O")) { "M860 missing O in $text" }
            assertTrue(text.contains(" X")) { "M860 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M860 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M860 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M860 missing E in $text" }
            assertTrue(text.contains(" U")) { "M860 missing U in $text" }
            assertTrue(text.contains(" P")) { "M860 missing P in $text" }
            assertTrue(text.contains(" S")) { "M860 missing S in $text" }
            assertTrue(text.contains(" R")) { "M860 missing R in $text" }
            assertTrue(text.contains(" T")) { "M860 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM860.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM861(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M861 missing I in $text" }
            assertTrue(text.contains(" O")) { "M861 missing O in $text" }
            assertTrue(text.contains(" X")) { "M861 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M861 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M861 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M861 missing E in $text" }
            assertTrue(text.contains(" U")) { "M861 missing U in $text" }
            assertTrue(text.contains(" P")) { "M861 missing P in $text" }
            assertTrue(text.contains(" S")) { "M861 missing S in $text" }
            assertTrue(text.contains(" R")) { "M861 missing R in $text" }
            assertTrue(text.contains(" T")) { "M861 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM861.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM862(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M862 missing I in $text" }
            assertTrue(text.contains(" O")) { "M862 missing O in $text" }
            assertTrue(text.contains(" X")) { "M862 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M862 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M862 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M862 missing E in $text" }
            assertTrue(text.contains(" U")) { "M862 missing U in $text" }
            assertTrue(text.contains(" P")) { "M862 missing P in $text" }
            assertTrue(text.contains(" S")) { "M862 missing S in $text" }
            assertTrue(text.contains(" R")) { "M862 missing R in $text" }
            assertTrue(text.contains(" T")) { "M862 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM862.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM863(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M863 missing I in $text" }
            assertTrue(text.contains(" O")) { "M863 missing O in $text" }
            assertTrue(text.contains(" X")) { "M863 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M863 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M863 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M863 missing E in $text" }
            assertTrue(text.contains(" U")) { "M863 missing U in $text" }
            assertTrue(text.contains(" P")) { "M863 missing P in $text" }
            assertTrue(text.contains(" S")) { "M863 missing S in $text" }
            assertTrue(text.contains(" R")) { "M863 missing R in $text" }
            assertTrue(text.contains(" T")) { "M863 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM863.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM864(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M864 missing I in $text" }
            assertTrue(text.contains(" O")) { "M864 missing O in $text" }
            assertTrue(text.contains(" X")) { "M864 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M864 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M864 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M864 missing E in $text" }
            assertTrue(text.contains(" U")) { "M864 missing U in $text" }
            assertTrue(text.contains(" P")) { "M864 missing P in $text" }
            assertTrue(text.contains(" S")) { "M864 missing S in $text" }
            assertTrue(text.contains(" R")) { "M864 missing R in $text" }
            assertTrue(text.contains(" T")) { "M864 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM864.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM865(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M865 missing I in $text" }
            assertTrue(text.contains(" O")) { "M865 missing O in $text" }
            assertTrue(text.contains(" X")) { "M865 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M865 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M865 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M865 missing E in $text" }
            assertTrue(text.contains(" U")) { "M865 missing U in $text" }
            assertTrue(text.contains(" P")) { "M865 missing P in $text" }
            assertTrue(text.contains(" S")) { "M865 missing S in $text" }
            assertTrue(text.contains(" R")) { "M865 missing R in $text" }
            assertTrue(text.contains(" T")) { "M865 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM865.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM866(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M866 missing I in $text" }
            assertTrue(text.contains(" O")) { "M866 missing O in $text" }
            assertTrue(text.contains(" X")) { "M866 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M866 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M866 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M866 missing E in $text" }
            assertTrue(text.contains(" U")) { "M866 missing U in $text" }
            assertTrue(text.contains(" P")) { "M866 missing P in $text" }
            assertTrue(text.contains(" S")) { "M866 missing S in $text" }
            assertTrue(text.contains(" R")) { "M866 missing R in $text" }
            assertTrue(text.contains(" T")) { "M866 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM866.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM867(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M867 missing I in $text" }
            assertTrue(text.contains(" O")) { "M867 missing O in $text" }
            assertTrue(text.contains(" X")) { "M867 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M867 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M867 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M867 missing E in $text" }
            assertTrue(text.contains(" U")) { "M867 missing U in $text" }
            assertTrue(text.contains(" P")) { "M867 missing P in $text" }
            assertTrue(text.contains(" S")) { "M867 missing S in $text" }
            assertTrue(text.contains(" R")) { "M867 missing R in $text" }
            assertTrue(text.contains(" T")) { "M867 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM867.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM868(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M868 missing I in $text" }
            assertTrue(text.contains(" O")) { "M868 missing O in $text" }
            assertTrue(text.contains(" X")) { "M868 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M868 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M868 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M868 missing E in $text" }
            assertTrue(text.contains(" U")) { "M868 missing U in $text" }
            assertTrue(text.contains(" P")) { "M868 missing P in $text" }
            assertTrue(text.contains(" S")) { "M868 missing S in $text" }
            assertTrue(text.contains(" R")) { "M868 missing R in $text" }
            assertTrue(text.contains(" T")) { "M868 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM868.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        I2CPositionEncodersM869(index = 1, o = true, axis = true, y = true, z = true, e = true, u = true, p = 1, addr = 1, r = true, t = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M869 missing I in $text" }
            assertTrue(text.contains(" O")) { "M869 missing O in $text" }
            assertTrue(text.contains(" X")) { "M869 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M869 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M869 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M869 missing E in $text" }
            assertTrue(text.contains(" U")) { "M869 missing U in $text" }
            assertTrue(text.contains(" P")) { "M869 missing P in $text" }
            assertTrue(text.contains(" S")) { "M869 missing S in $text" }
            assertTrue(text.contains(" R")) { "M869 missing R in $text" }
            assertTrue(text.contains(" T")) { "M869 missing T in $text" }
            assertEquals(it, I2CPositionEncodersM869.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ProbeTemperatureConfig(value = 1, index = 1, b = true, p = true, e = true, r = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" V")) { "M871 missing V in $text" }
            assertTrue(text.contains(" I")) { "M871 missing I in $text" }
            assertTrue(text.contains(" B")) { "M871 missing B in $text" }
            assertTrue(text.contains(" P")) { "M871 missing P in $text" }
            assertTrue(text.contains(" E")) { "M871 missing E in $text" }
            assertTrue(text.contains(" R")) { "M871 missing R in $text" }
            assertEquals(it, ProbeTemperatureConfig.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        HandlePromptResponse(response = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M876 missing S in $text" }
            assertEquals(it, HandlePromptResponse.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        LinearAdvanceFactor(kfactor = BigDecimal("1.5"), l = BigDecimal("1.5"), slot = 1, index = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" K")) { "M900 missing K in $text" }
            assertTrue(text.contains(" L")) { "M900 missing L in $text" }
            assertTrue(text.contains(" S")) { "M900 missing S in $text" }
            assertTrue(text.contains(" T")) { "M900 missing T in $text" }
            assertEquals(it, LinearAdvanceFactor.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        StepperMotorCurrent(e = 1, i = 1, t = 1, x = 1, y = 1, z = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" E")) { "M906 missing E in $text" }
            assertTrue(text.contains(" I")) { "M906 missing I in $text" }
            assertTrue(text.contains(" T")) { "M906 missing T in $text" }
            assertTrue(text.contains(" X")) { "M906 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M906 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M906 missing Z in $text" }
            assertEquals(it, StepperMotorCurrent.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TrimpotStepperMotorCurrent(current = BigDecimal("1.5"), c = BigDecimal("1.5"), d = BigDecimal("1.5"), e = BigDecimal("1.5"), s = BigDecimal("1.5"), x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), i = BigDecimal("1.5"), j = BigDecimal("1.5"), k = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" B")) { "M907 missing B in $text" }
            assertTrue(text.contains(" C")) { "M907 missing C in $text" }
            assertTrue(text.contains(" D")) { "M907 missing D in $text" }
            assertTrue(text.contains(" E")) { "M907 missing E in $text" }
            assertTrue(text.contains(" S")) { "M907 missing S in $text" }
            assertTrue(text.contains(" X")) { "M907 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M907 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M907 missing Z in $text" }
            assertTrue(text.contains(" I")) { "M907 missing I in $text" }
            assertTrue(text.contains(" J")) { "M907 missing J in $text" }
            assertTrue(text.contains(" K")) { "M907 missing K in $text" }
            assertTrue(text.contains(" U")) { "M907 missing U in $text" }
            assertTrue(text.contains(" V")) { "M907 missing V in $text" }
            assertTrue(text.contains(" W")) { "M907 missing W in $text" }
            assertEquals(it, TrimpotStepperMotorCurrent.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetTrimpotPins(address = 1, current = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" P")) { "M908 missing P in $text" }
            assertTrue(text.contains(" S")) { "M908 missing S in $text" }
            assertEquals(it, SetTrimpotPins.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        ClearTMCOTPreWarn(i = 1, x = true, y = true, z = true, e = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M912 missing I in $text" }
            assertTrue(text.contains(" X")) { "M912 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M912 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M912 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M912 missing E in $text" }
            assertEquals(it, ClearTMCOTPreWarn.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SetHybridThresholdSpeed(i = 1, t = 1, x = true, y = true, z = true, a = 1, b = 1, c = 1, u = 1, v = 1, w = 1, e = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M913 missing I in $text" }
            assertTrue(text.contains(" T")) { "M913 missing T in $text" }
            assertTrue(text.contains(" X")) { "M913 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M913 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M913 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M913 missing A in $text" }
            assertTrue(text.contains(" B")) { "M913 missing B in $text" }
            assertTrue(text.contains(" C")) { "M913 missing C in $text" }
            assertTrue(text.contains(" U")) { "M913 missing U in $text" }
            assertTrue(text.contains(" V")) { "M913 missing V in $text" }
            assertTrue(text.contains(" W")) { "M913 missing W in $text" }
            assertTrue(text.contains(" E")) { "M913 missing E in $text" }
            assertEquals(it, SetHybridThresholdSpeed.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TMCBumpSensitivity(i = 1, x = 1, y = 1, z = 1, a = 1, b = 1, c = 1, u = 1, v = 1, w = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M914 missing I in $text" }
            assertTrue(text.contains(" X")) { "M914 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M914 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M914 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M914 missing A in $text" }
            assertTrue(text.contains(" B")) { "M914 missing B in $text" }
            assertTrue(text.contains(" C")) { "M914 missing C in $text" }
            assertTrue(text.contains(" U")) { "M914 missing U in $text" }
            assertTrue(text.contains(" V")) { "M914 missing V in $text" }
            assertTrue(text.contains(" W")) { "M914 missing W in $text" }
            assertEquals(it, TMCBumpSensitivity.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TMCZAxisCalibration(s = 1, z = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M915 missing S in $text" }
            assertTrue(text.contains(" Z")) { "M915 missing Z in $text" }
            assertEquals(it, TMCZAxisCalibration.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        L6474ThermalWarningTest(j = 1, x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), e = BigDecimal("1.5"), feedrate = 1, current = 1, k = 1, second = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" J")) { "M916 missing J in $text" }
            assertTrue(text.contains(" X")) { "M916 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M916 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M916 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M916 missing E in $text" }
            assertTrue(text.contains(" F")) { "M916 missing F in $text" }
            assertTrue(text.contains(" T")) { "M916 missing T in $text" }
            assertTrue(text.contains(" K")) { "M916 missing K in $text" }
            assertTrue(text.contains(" D")) { "M916 missing D in $text" }
            assertEquals(it, L6474ThermalWarningTest.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        L6474OvercurrentWarningTest(j = 1, x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), a = BigDecimal("1.5"), b = BigDecimal("1.5"), c = BigDecimal("1.5"), u = BigDecimal("1.5"), v = BigDecimal("1.5"), w = BigDecimal("1.5"), e = BigDecimal("1.5"), feedrate = 1, current = 1, t = 1, k = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" J")) { "M917 missing J in $text" }
            assertTrue(text.contains(" X")) { "M917 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M917 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M917 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M917 missing A in $text" }
            assertTrue(text.contains(" B")) { "M917 missing B in $text" }
            assertTrue(text.contains(" C")) { "M917 missing C in $text" }
            assertTrue(text.contains(" U")) { "M917 missing U in $text" }
            assertTrue(text.contains(" V")) { "M917 missing V in $text" }
            assertTrue(text.contains(" W")) { "M917 missing W in $text" }
            assertTrue(text.contains(" E")) { "M917 missing E in $text" }
            assertTrue(text.contains(" F")) { "M917 missing F in $text" }
            assertTrue(text.contains(" I")) { "M917 missing I in $text" }
            assertTrue(text.contains(" T")) { "M917 missing T in $text" }
            assertTrue(text.contains(" K")) { "M917 missing K in $text" }
            assertEquals(it, L6474OvercurrentWarningTest.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        L6474SpeedWarningTest(j = 1, x = BigDecimal("1.5"), y = BigDecimal("1.5"), z = BigDecimal("1.5"), e = BigDecimal("1.5"), current = 1, t = 1, k = 1, microsteps = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" J")) { "M918 missing J in $text" }
            assertTrue(text.contains(" X")) { "M918 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M918 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M918 missing Z in $text" }
            assertTrue(text.contains(" E")) { "M918 missing E in $text" }
            assertTrue(text.contains(" I")) { "M918 missing I in $text" }
            assertTrue(text.contains(" T")) { "M918 missing T in $text" }
            assertTrue(text.contains(" K")) { "M918 missing K in $text" }
            assertTrue(text.contains(" M")) { "M918 missing M in $text" }
            assertEquals(it, L6474SpeedWarningTest.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TMCChopperTiming(o = 1, p = 1, s = 1, i = 1, t = 1, x = true, y = true, z = true, a = true, b = true, c = true, u = true, v = true, w = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" O")) { "M919 missing O in $text" }
            assertTrue(text.contains(" P")) { "M919 missing P in $text" }
            assertTrue(text.contains(" S")) { "M919 missing S in $text" }
            assertTrue(text.contains(" I")) { "M919 missing I in $text" }
            assertTrue(text.contains(" T")) { "M919 missing T in $text" }
            assertTrue(text.contains(" X")) { "M919 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M919 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M919 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M919 missing A in $text" }
            assertTrue(text.contains(" B")) { "M919 missing B in $text" }
            assertTrue(text.contains(" C")) { "M919 missing C in $text" }
            assertTrue(text.contains(" U")) { "M919 missing U in $text" }
            assertTrue(text.contains(" V")) { "M919 missing V in $text" }
            assertTrue(text.contains(" W")) { "M919 missing W in $text" }
            assertEquals(it, TMCChopperTiming.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        TMCHomingCurrent(i = 1, x = 1, y = 1, z = 1, a = 1, b = 1, c = 1, u = 1, v = 1, w = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" I")) { "M920 missing I in $text" }
            assertTrue(text.contains(" X")) { "M920 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M920 missing Y in $text" }
            assertTrue(text.contains(" Z")) { "M920 missing Z in $text" }
            assertTrue(text.contains(" A")) { "M920 missing A in $text" }
            assertTrue(text.contains(" B")) { "M920 missing B in $text" }
            assertTrue(text.contains(" C")) { "M920 missing C in $text" }
            assertTrue(text.contains(" U")) { "M920 missing U in $text" }
            assertTrue(text.contains(" V")) { "M920 missing V in $text" }
            assertTrue(text.contains(" W")) { "M920 missing W in $text" }
            assertEquals(it, TMCHomingCurrent.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MagneticParkingExtruder(l = BigDecimal("1.5"), r = BigDecimal("1.5"), i = BigDecimal("1.5"), j = BigDecimal("1.5"), h = BigDecimal("1.5"), d = BigDecimal("1.5"), c = BigDecimal("1.5")).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" L")) { "M951 missing L in $text" }
            assertTrue(text.contains(" R")) { "M951 missing R in $text" }
            assertTrue(text.contains(" I")) { "M951 missing I in $text" }
            assertTrue(text.contains(" J")) { "M951 missing J in $text" }
            assertTrue(text.contains(" H")) { "M951 missing H in $text" }
            assertTrue(text.contains(" D")) { "M951 missing D in $text" }
            assertTrue(text.contains(" C")) { "M951 missing C in $text" }
            assertEquals(it, MagneticParkingExtruder.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        STOPRestart(s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" S")) { "M999 missing S in $text" }
            assertEquals(it, STOPRestart.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        MAX7219Control(column = 1, row = 1, r = 1, i = true, f = true, p = true, index = 1, bits = 1L, x = 1, y = 1).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" C")) { "M7219 missing C in $text" }
            assertTrue(text.contains(" D")) { "M7219 missing D in $text" }
            assertTrue(text.contains(" R")) { "M7219 missing R in $text" }
            assertTrue(text.contains(" I")) { "M7219 missing I in $text" }
            assertTrue(text.contains(" F")) { "M7219 missing F in $text" }
            assertTrue(text.contains(" P")) { "M7219 missing P in $text" }
            assertTrue(text.contains(" U")) { "M7219 missing U in $text" }
            assertTrue(text.contains(" V")) { "M7219 missing V in $text" }
            assertTrue(text.contains(" X")) { "M7219 missing X in $text" }
            assertTrue(text.contains(" Y")) { "M7219 missing Y in $text" }
            assertEquals(it, MAX7219Control.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectOrReportToolT0(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T0 missing F in $text" }
            assertTrue(text.contains(" S")) { "T0 missing S in $text" }
            assertEquals(it, SelectOrReportToolT0.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectOrReportToolT1(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T1 missing F in $text" }
            assertTrue(text.contains(" S")) { "T1 missing S in $text" }
            assertEquals(it, SelectOrReportToolT1.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectOrReportToolT2(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T2 missing F in $text" }
            assertTrue(text.contains(" S")) { "T2 missing S in $text" }
            assertEquals(it, SelectOrReportToolT2.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectOrReportToolT3(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T3 missing F in $text" }
            assertTrue(text.contains(" S")) { "T3 missing S in $text" }
            assertEquals(it, SelectOrReportToolT3.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectOrReportToolT4(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T4 missing F in $text" }
            assertTrue(text.contains(" S")) { "T4 missing S in $text" }
            assertEquals(it, SelectOrReportToolT4.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectOrReportToolT5(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T5 missing F in $text" }
            assertTrue(text.contains(" S")) { "T5 missing S in $text" }
            assertEquals(it, SelectOrReportToolT5.decodeParams(it.encode().params)) { "round trip: $text" }
        }
    }

    @Test
    fun `every parameter is written and read back 6`() {
        SelectOrReportToolT6(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T6 missing F in $text" }
            assertTrue(text.contains(" S")) { "T6 missing S in $text" }
            assertEquals(it, SelectOrReportToolT6.decodeParams(it.encode().params)) { "round trip: $text" }
        }
        SelectOrReportToolT7(feedrate = BigDecimal("1.5"), s = true).let {
            val text = GEncoder.encode(it.encode())
            assertTrue(text.contains(" F")) { "T7 missing F in $text" }
            assertTrue(text.contains(" S")) { "T7 missing S in $text" }
            assertEquals(it, SelectOrReportToolT7.decodeParams(it.encode().params)) { "round trip: $text" }
        }
    }

}
