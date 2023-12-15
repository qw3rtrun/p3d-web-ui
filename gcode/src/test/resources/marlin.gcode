G0 X12         ; Move to 12mm on the X axis
G0 F1500       ; Set the feedrate to 1500 mm/min
G1 X90.6 Y13.8 ; Move to 90.6mm on the X axis and 13.8mm on the Y axis
G1 F1500           ; Set the feedrate to 1500 mm/min
G92 E0
G1 X50 Y25.3 E22.4 ; Move while extruding
G1 F1500
G92 E0
G1 X50 Y25.3 E22.4 F3000

G2 X125 Y32 I10.5 J10.5
G3 X125 Y32 I10.5 J10.5
G2 I20 J20

G4 P500 ; Dwell for 1/2 second

G0 X0 Y0
G5 I0 J3 P0 Q-3 X1 Y1
G5 P0 Q-3 X2 Y2

G10 ; retract
G11 ; recover

G12 ; stroke pattern (default)
G12 P1 S1 T3 ; zig-zag pattern with 3 triangles
G12 P2 S1 R10 ; 10mm circle

G17
G18
G19

G20 ; set units to inches
G21 ; set units to millimeters
G27 ; Raise Z if lower
G27 P2 ; Always raise Z

G28 ; Home all axes
G28 X Z ; Home the X and Z axes
G28 O ; Home all "untrusted" axes

G28           ; Home XYZ.
G29 P1        ; Do automated probing of the bed.
G29 P3        ; Smart Fill Repeat until all mesh points are filled in, Used to fill unreachable points.
G29 S0        ; Save UBL mesh points to slot 0 (EEPROM).
G29 F 10.0    ; Set Fade Height for correction at 10.0 mm.
G29 A         ; Activate the UBL System.

G28           ; Home XYZ.
G29 P4 R255   ; Do manual probing of the bed.
G29 S0        ; Save UBL mesh points to slot 0 (EEPROM).
G29 F 10.0    ; Set Fade Height for correction at 10.0 mm.
G29 A         ; Activate the UBL System.

M190 S65      ; Heat Bed to 65C. Not required, but having the printer at temperature may help accuracy.
M104 S210     ; Heat Hotend to 210C. Not required, but having the printer at temperature may help accuracy.

G29 T         ; View the Z compensation values.
G29 P2 B T    ; Manually probe unreachable points. Requires an LCD controller.

G26 C P T3.0  ; Produce mesh validation pattern with primed nozzle. G26 is optional; any bed leveling stl would also work.
G29 P4 T      ; Move nozzle to 'bad' areas and fine tune the values if needed.
G29 S0        ; Save UBL mesh values to EEPROM.

G29 L0        ; Load the mesh stored in slot 0 (from G29 S0)
G29 J         ; Probe 3 points and tilt the mesh according to what it finds, optionally G29 J2 would do 4 points.

G33 P1
G33 V0
G33 P2
G33 T

G35 S40

G42 I0 J0 ; front left corner
G42 I4 J4 ; center
G42 I4 J8 ; back center
G42 I8 J8 ; back right

G53 G0 X0 Y0 Z0
G53 G1 X20
G53

G55 ; Select workspace 1
G0 X0 Y0 X0

G60 S0

G61 XY S0

G76 ; calibrate bed, then probe
G76 B ; calibrate bed only
G76 P ; calibrate probe only

G92 X10 E90
G92 X0 Y0 Z0
G92 .1 ;TODO

M0 Click to continue

M3 O128
M3
M3 S204 I


M4 S50
M4 S128
M4 O204
M4
M4 S204 I

M16 Arthur

M17 Z E
M18 S60
M18 Z E

M23 /musicg\~1/jingle.gco

M27 S4
M27 S0
M27 C

M28 file.txt
M28 B1 file.txt

M30 /path/to/file.gco

M32 S5022 !/boats/sailboat.gco
M32 P !/models/lgbust.gco#

M33 funstuff/mask.gco

M42 S1
M42 P33 S1
M42 P44 S128

M43 ;Get a report on all pins, ignore pin protection list when displaying values
M43 I ;Watch pin 56 for changes
M43 P56 W ;Start watching endstops
M43 E1 ;Toggle pins 3-6 five times with 1 second low and 1 second high pulses but only if the pin isn’t in the protected list.
M43 T S3 L6 R5 W1000 ;Test probe controlled by servo index 2.
M43 S P2

M43 T S3 L6 R5 W1000

M73 P25 R43

M80 ; power on
M81 ; power off

M85 S600

M86 S240 T100 E40 B30

M92 E688.4

M104 S185 ;set target temperature for E1
M104 T1 S205 ;AUTOTEMP: Set autotemp range
M104 F S180 B190 ;AUTOTEMP: Disable autotemp
M104

M106 S200

M0 You're up, mate ; in your G-code file
M108               ; as your "Continue" button

M109 S180 ;Set target temperature, wait even if cooling
M109 R120 ;Set target temperature for E1 and wait (if heating up)
M109 T1 R205 ;AUTOTEMP: Set autotemp range, wait for temp
M109 F S180 B190 ;AUTOTEMP: Disable autotemp, wait for temp
M109

N100 M110
M110 N100
N101 M110 N100

M111 S38 ; LEVELING, ERRORS, INFO
M111 S8
M111 S247 ; 255 - 8
M111 S0

M117 Hello World!
M117 "Hello World!"

M118 E1 Yello World!
M118 A1 action:cancel

M123 S5

M125 L20 ; park and retract
M126 ; open valve 1
M127 ; close valve 1

M141 S40

M143 S15
M143 S0

M145 S0 H190 B70 F50

M150 B30
M150 R100 I0
M150 U45 I1
M150 W255 S1
M150 K R127

M163 S0 P0.6
M163 S1 P0.4
M164 S5

M163 S0 P3
M163 S1 P5
M164 S4

M163 S0 P0.6
M163 S1 P0.4
M164 S5

M163 S0 P3
M163 S1 P5
M164 S4

M165 A0.2 B0.4 C0.3 D0.1

M166 A0 Z250 I0 J1 S1
M166 A0 Z250 I0 J1 T3
M166 T

M190 S80
M190 R40
M190 R70 T600

M200 D2.85
M200 S1 D1.75
M200 S0 D1.75

M201 X50 Y50 ;Set the max acceleration for E1 print moves:
M201 E8000 T1 ;Set frequency limits for the XY axes:
M201 F60 S47

M203 X100 Y100

M204 P2400

M205 T40 ; Travel feedrate = 40mm/s

M206 Z-0.2
M206 X10 ;Shift the print area 10mm to the left

M208 S0.2 F600
M207 S2.0  ; 2.0mm retract
M208 S0.5  ; 2.0mm + 0.5mm recover

M260 A99  ; Target slave address
M260 B77  ; M
M260 B97  ; a
M260 B114 ; r
M260 B108 ; l
M260 B105 ; i
M260 B110 ; n
M260 S1   ; Send the current buffer
M261 A99 B5 ; i2c-reply: from:99 bytes:5 data:hello

M281 P0 L90 U0
M282 P1
M290 Z0.25 ; move up 0.25mm on the Z axis

M300 S440 P200
M300 S660 P250
M300 S880 P300

M302         ; report current cold extrusion state
M302 P0      ; enable cold extrusion checking
M302 P1      ; disable cold extrusion checking
M302 S0      ; always allow extrusion (disable checking)
M302 S170    ; only allow extrusion above 170
M302 S170 P1 ; set min extrude temp to 170 but leave disabled

M303 E0 C8 S210
M303 E-1 C8 S60

M305 P0 R4700 T100000 B3950 C0.0

M351 S1 X1 E0

M355 S1 P128

M403 E0 F1
M403 E3 F2

M404 W1.75

M412 S1
M412 S0
M412
M412 D35 ; requires FILAMENT_RUNOUT_DISTANCE_MM

M413 S1
M413 S0
M413 ;Power-loss recovery ON

M421 I2 J2 Z-0.05
M421 X100 Y100 Z-0.05
M421 I2 J2 Q-0.01

M422 S1 X10 Y10
M422 W2 X10 Y10

M423
M423 R
M423 X4 Z-0.03
M423 X0 I50

M425 X0.1 Y0.2 Z0.3 ; Set backlash to specific values for all axis
M425 F1             ; Enable backlash compensation at 100%
M425 F1 S3
M425 F1 S0
G425                ; Perform a full calibration
M425 F1             ; Use full measured value of backlash on X, Y and Z
G29                 ; Perform probe and measure backlash on Z
M425 F1 Z           ; Use full measured value of backlash on Z

M430 I1 V1 W1

M486 T12 ; Total of 12 objects (otherwise the firmware must count)
M486 S3  ; Indicate that the 4th object is starting now
M486 S-1 ; Indicate a non-object, purge tower, or other global feature
M486 P10 ; Cancel object with index 10 (the 11th object)
M486 U2  ; Un-cancel object with index 2 (the 3rd object)
M486 C   ; Cancel the current object (use with care!)

M493 S1 P1 K0.22
M493 S11 A37 B37 D0 P1 K0.18

M502 ; reset
M500 ; saved

M511 P12345
M512 P1234 S9090

M569 S1 Z E
M569 S0 X Y
M569 S1 I1 X T1 E
M569

M575 P0 B115200
M575 B250000
M592 A0.2 B0.3

M593 X F18.4
M593 F36.2
M593 F0

M600 ; execute filament change
M600 X10 Y15 Z5 ; Do filament change at X:10, Y:15 and Z:+5 from current

M603 U120 L125

M605 S2
M605 S2 E2
M605 S2 P5

M710 A0 S255
M710 A1 S255 I128
M710 R

M808 L5
M118 Hello World
M808

M815 G0 X0 Y0|G0 Z10|M300 S440 P50

M851 Z-2.0
M851 Z1.2
M851 X-1.70 Y-1.30
M851 X0.20 Y.40

M871 ; print current values
M871 R ; reset all values factory default (zero, effectively disabling compensation)
M871 P I1 V-5 ; set probe compensation value at index 1 to -5µm
M871 B I2 V20 ; set bed compensation value at index 2 to 20µm
M871 E I4 V-13 ; set extruder compensation value at index 4 to -13µm

M876 S0

M900 S0            ; Select main K factor and apply it
M900 T2 K0.22 L0.4 ; Set both T2 K factors. K0.22 will be applied.
M900 T2 S1         ; Select extra K factor. L0.4 will be applied.
M900 T2 S1         ; (does nothing this time)
M900 T2 L0.3       ; Set T2 extra (and active) K factor
M900 T2 S0         ; Select main K factor (0.22)

M906 X5 Y5 Z5
M906 T1 E10
M906 I1 X5

M912     ; clear all
M912 X   ; clear X and X2
M912 X1  ; clear X1 only
M912 X2  ; clear X2 only
M912 X E ; clear X, X2, and all E
M912 E1  ; clear E1 only

M913 X100
M913 X100 Y120 E30

M915 S300 Z5

M919
M919 XYZE O3 P-1 S1
M919 Z O3 P-1 S1
M919 Z I1 O3 P-1 S1

M928 log.txt
