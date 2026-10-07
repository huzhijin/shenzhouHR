## 1. Leave types and weekend hours

- [x] 1.1 Add `FAMILY_PLANNING` and `OTHER` to `LeaveType` / OA catalog mapping so 计生假 and 其他 are not quarantined
- [x] 1.2 Recognize leave hours from shift WORK segments (扬州 3.5/4.5，大连 4.5/3.5，成都用已发布段)
- [x] 1.3 Include Saturday, Sunday, and public holidays for 婚假、计生假、陪产假、产假、病假、丧假、孕检假、哺乳假、其他；weekend without segments uses weekday template minutes
- [x] 1.4 Exclude Saturday, Sunday, and public holidays for 年休假、调休假、事假
- [x] 1.5 Point leave-report `recognized-hours` at this recognition; cover 计生假 and 事假跨周末 in tests

## 2. Scheduled punches and overnight window

- [x] 2.1 Keep scheduled pairing as earliest arrival-window punch and latest departure-window punch; 17:30 is not required when overtime continues
- [x] 2.2 Extend the previous day's evidence window to next-day `shiftStart`; do not use 06:00
- [x] 2.3 Attach punches in `[00:00, shiftStart)` to the previous day as overtime off-duty; they MUST NOT be next-day on-duty
- [x] 2.4 Allow overnight off-duty then same-morning work with no rest gap (05:50 then 08:32 is normal)
- [x] 2.5 Tests: multiple same-day punches; Monday 08:30 + Tuesday 07:00 + Tuesday 08:32; Dalian `shiftStart` 07:30

## 3. Overnight leave combo, missing punch, fake overtime

- [x] 3.1 Treat overtime ending before next `shiftStart` plus morning leave as valid; Tuesday morning slot shows leave, not 07:00
- [x] 3.2 If no punch in `[00:00, shiftStart)` and no morning leave, record Monday missing off-duty and prompt supplement
- [x] 3.3 Add fake-overtime when overtime overlaps scheduled work not covered by leave; recognized minutes 0
- [x] 3.4 Do not mark fake overtime for overtime that ends before `shiftStart`
- [x] 3.5 Tests: 加到 07:00 + 上午请假；忘打离开卡；加班盖住未请假上午

## 4. Weekend overtime meals (HR)

- [x] 4.1 Saturday, Sunday, and public-holiday lunch rest is always 12:00–13:00 (60 minutes on overlap)
- [x] 4.2 Dinner rest remains 30 minutes from published shift off time (winter 17:30–18:00, summer 18:00–18:30)
- [x] 4.3 Test summer weekend 13:00–13:30 does not take the lunch hour

## 5. Half-day attendance rate

- [x] 5.1 Change `actualAttendanceDays` from integer 0/1 to `DECIMAL(3,1)` values 0 / 0.5 / 1; bump formula catalog
- [x] 5.2 Count 0.5 per morning or afternoon segment; 事假 does not count; paid leave does
- [x] 5.3 Attendance-rate report uses the decimal sum; test morning work + afternoon 事假 = 0.5

## 6. Monthly work-hours sheet

- [x] 6.1 Expose columns 姓名, 部门, `{n}月应出勤工时`, 加班时数, 事假+病假+其他假期, 年假, 加班换调休, 实际调休, 个人实际出勤工时
- [x] 6.2 加班时数 = paid overtime only; 加班换调休 = compensatory overtime; 实际调休 = used TIME_OFF (stop mapping used time-off onto 加班换调休)
- [x] 6.3 Compute `个人实际出勤 = 应出勤 + 计薪加班 − (事假+病假+其他) − 年假 + 加班换调休 − 实际调休` in the calculator
- [x] 6.4 Update OpenAPI, frontend sheet, export, and demo so they do not subtract 加班换调休
- [x] 6.5 Test the 176+8-8-8+4-8 = 164 example

## 7. Exception overview

- [x] 7.1 Show chargeable late, early departure, scheduled missing punches, overnight missing off-duty, fake overtime, and 旷工
- [x] 7.2 Hide grace late (penalized 0), rest-day no punches, exempt days, supplemented punches, and pending overtime that already has a leaving punch
- [x] 7.3 Stop projecting absence as `MISSING_PUNCH_OVERDUE`
- [x] 7.4 Fake-overtime evidence summary names the overlapping unleaved work window
- [x] 7.5 Frontend labels include 虚假加班; empty overview is only valid when none of 7.1 apply

## 8. Month matrix cells

- [x] 8.1 Tuesday morning slot is Tuesday arrival-window punch or leave, never the overnight leaving punch
- [x] 8.2 Monday can show next-morning leaving time as off-duty; overtime color only after an approved overtime document
- [x] 8.3 Half-day hover hours come from shift segments, not hardcoded Yangzhou 3.5/4.5
- [x] 8.4 Tests for 07:00 + 上午请假 grid text and Dalian 4.5 morning hover

## 9. OA plugin (`/Users/huzhijin/Downloads/szsc`)

- [x] 9.1 Remove `CROSS_DAY_CUTOFF = 06:00`; overnight proof is a punch strictly before next-day `shiftStart`
- [x] 9.2 Allow Monday 08:30 + Tuesday 07:00 filing of Monday 17:30–Tuesday 07:00 for a 08:30 shift
- [x] 9.3 Reject overnight filing when there is no punch before `shiftStart`; message asks to 补卡
- [x] 9.4 Subtract effective leave from scheduled work before overlap rejection; reject overtime into unleaved work with an explicit message
- [x] 9.5 Treat `[00:00, shiftStart)` as overnight continuation so "only after workEnd" does not fire on 00:00–07:00
- [x] 9.6 Weekend and holiday lunch window is 12:00–13:00; dinner stays shift-off + 30 minutes
- [x] 9.7 Load leave intervals from HR for the applicant and dates
- [x] 9.8 Tests: 07:00 + 上午请假 passes; 08:32-only Tuesday fails; unleaved 08:30–10:00 OT fails; summer weekend 13:00–13:30 does not deduct lunch
- [x] 9.9 Resolve `OPEN-DECISIONS.md` 06:00 item: cutoff is next-day shift start, not 06:00

## 10. Formula version, recalculate, and regression

- [x] 10.1 Bump report formula catalog so old pins are not mixed with new days/hours
- [x] 10.2 Keep existing tests for paid/compensatory/voluntary overtime classification and monthly grace late
- [x] 10.3 After deploy, HR admin recalculates open months; document that OA overlay and HR must ship in the same window
