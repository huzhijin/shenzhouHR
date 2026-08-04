-- Expose the already supported weekend meal policy fields in the persisted
-- production catalog. Published scoped versions and binding history are
-- intentionally untouched: an administrator creates, validates, publishes
-- and binds one immutable version per half-open seasonal period.
UPDATE attendance_policy_template
SET name = '晚餐扣除',
    description = '按已发布生效期版本配置工作日、周末及法定节假日午餐和晚餐扣除',
    field_definitions_json = JSON_ARRAY(
        JSON_OBJECT(
            'key', 'enabled',
            'label', '是否启用',
            'valueType', 'BOOLEAN',
            'required', TRUE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'mealWindowStart',
            'label', '晚餐窗口开始',
            'valueType', 'LOCAL_TIME',
            'required', TRUE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'mealWindowEnd',
            'label', '晚餐窗口结束',
            'valueType', 'LOCAL_TIME',
            'required', TRUE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'deductionMinutes',
            'label', '扣除分钟',
            'valueType', 'INTEGER',
            'required', TRUE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 240
        ),
        JSON_OBJECT(
            'key', 'triggerMinutes',
            'label', '触发门槛分钟',
            'valueType', 'INTEGER',
            'required', TRUE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 1440
        ),
        JSON_OBJECT(
            'key', 'applicableDayTypes',
            'label', '适用日期类型',
            'valueType', 'ENUM_LIST',
            'required', TRUE,
            'enumValues', JSON_ARRAY(
                'WORKDAY',
                'SPECIAL_WORKDAY',
                'WEEKEND',
                'PUBLIC_HOLIDAY'
            )
        ),
        JSON_OBJECT(
            'key', 'saturdayMealWindowStart',
            'label', '周六晚餐窗口开始',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'saturdayMealWindowEnd',
            'label', '周六晚餐窗口结束',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'saturdayDeductionMinutes',
            'label', '周六晚餐扣除分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 240
        ),
        JSON_OBJECT(
            'key', 'saturdayTriggerMinutes',
            'label', '周六晚餐触发门槛分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 1440
        ),
        JSON_OBJECT(
            'key', 'saturdayLunchWindowStart',
            'label', '周六午餐窗口开始',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'saturdayLunchWindowEnd',
            'label', '周六午餐窗口结束',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'saturdayLunchDeductionMinutes',
            'label', '周六午餐扣除分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 240
        ),
        JSON_OBJECT(
            'key', 'saturdayLunchTriggerMinutes',
            'label', '周六午餐触发门槛分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 1440
        ),
        JSON_OBJECT(
            'key', 'sundayMealWindowStart',
            'label', '周日晚餐窗口开始',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'sundayMealWindowEnd',
            'label', '周日晚餐窗口结束',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'sundayDeductionMinutes',
            'label', '周日晚餐扣除分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 240
        ),
        JSON_OBJECT(
            'key', 'sundayTriggerMinutes',
            'label', '周日晚餐触发门槛分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 1440
        ),
        JSON_OBJECT(
            'key', 'sundayLunchWindowStart',
            'label', '周日午餐窗口开始',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'sundayLunchWindowEnd',
            'label', '周日午餐窗口结束',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'sundayLunchDeductionMinutes',
            'label', '周日午餐扣除分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 240
        ),
        JSON_OBJECT(
            'key', 'sundayLunchTriggerMinutes',
            'label', '周日午餐触发门槛分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 1440
        ),
        JSON_OBJECT(
            'key', 'publicHolidayMealWindowStart',
            'label', '法定节假日晚餐窗口开始',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'publicHolidayMealWindowEnd',
            'label', '法定节假日晚餐窗口结束',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'publicHolidayDeductionMinutes',
            'label', '法定节假日晚餐扣除分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 240
        ),
        JSON_OBJECT(
            'key', 'publicHolidayTriggerMinutes',
            'label', '法定节假日晚餐触发门槛分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 1440
        ),
        JSON_OBJECT(
            'key', 'publicHolidayLunchWindowStart',
            'label', '法定节假日午餐窗口开始',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'publicHolidayLunchWindowEnd',
            'label', '法定节假日午餐窗口结束',
            'valueType', 'LOCAL_TIME',
            'required', FALSE,
            'enumValues', JSON_ARRAY()
        ),
        JSON_OBJECT(
            'key', 'publicHolidayLunchDeductionMinutes',
            'label', '法定节假日午餐扣除分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 240
        ),
        JSON_OBJECT(
            'key', 'publicHolidayLunchTriggerMinutes',
            'label', '法定节假日午餐触发门槛分钟',
            'valueType', 'INTEGER',
            'required', FALSE,
            'enumValues', JSON_ARRAY(),
            'minimum', 0,
            'maximum', 1440
        )
    )
WHERE policy_template_id = '25000000-0000-0000-0000-000000000001'
  AND template_code = 'MEAL_DEDUCTION';
