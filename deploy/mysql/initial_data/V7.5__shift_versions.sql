-- ============================================================================
-- 班次版本（夏令时/冬令时具体配置）
-- ============================================================================

-- 重要字段说明：
-- effective_from: 生效日期（如 2026-01-01 为冬令时，2026-05-01 为夏令时）
-- segments_json: JSON 格式的工作时段配置
--   - WORK: 工作时段
--   - BREAK: 休息时段
--   - effectiveTo: 该配置有效期截止日期

INSERT INTO shift_version (
  shift_version_id, shift_template_id, version_number, effective_from,
  time_zone_snapshot, segments_json, supersedes_shift_version_id,
  snapshot_digest, change_reason, created_by, created_at
) VALUES
  ('590c0043-77f2-2be9-d510-ef7b5bbf9275', '41020000-0000-0000-0000-000000000001', 1, '2026-01-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-05-01"}', NULL,
   '49c8e4486d7c1fac1cc44e16675632c7a1584bfdc644dacd48763526813b585a', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('f6d77437-3a1c-00f2-c05b-50ce76ae2d67', '41020000-0000-0000-0000-000000000001', 2, '2026-05-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "18:00:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-10-01"}', NULL,
   'c0e6569f648a971c49763c21359d42899d968ec8c0ef19452f3a545fda06d359', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('e8af5f2a-b7bc-86f3-8483-3a97df7d9675', '41020000-0000-0000-0000-000000000001', 3, '2026-10-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2027-01-01"}', NULL,
   'c77b4c33b7e3ef68e3ec2cc8023a90b6b10097a8b8601177e36de0149fdc8f83', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('b22854ea-448a-49c0-1c89-a69d7927cdf1', '41020000-0000-0000-0000-000000000002', 1, '2026-01-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-05-01"}', NULL,
   '17c7e2b7280b61b4a6ff67d48f3215233a879ab33c055ec730308f420d1bbae2', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('be113452-2627-8cb7-4285-8efdf6770f04', '41020000-0000-0000-0000-000000000002', 2, '2026-05-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "18:00:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-10-01"}', NULL,
   'ec0db3c8f2b6a73d4c6012e502c3209ef79aef3d5dacd0f6a4c1a6a53514c1a4', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('cc63b369-6aaa-935e-905b-ed188c2ee645', '41020000-0000-0000-0000-000000000002', 3, '2026-10-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2027-01-01"}', NULL,
   'e85eaac696b3c495f2da842d80033776f8327354ebaed72dadf9df828fdd95c1', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('47290f4d-215b-4366-7bef-1421cf63d3ee', '41020000-0000-0000-0000-000000000003', 1, '2026-01-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-05-01"}', NULL,
   '34cd0868af1e96e50207d0b021882112e1c1c98a6785b8d4ef00cad604018f16', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('e10a1de9-4c4a-782c-9212-aeea577a879a', '41020000-0000-0000-0000-000000000003', 2, '2026-05-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "18:00:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-10-01"}', NULL,
   'b7f2312ab43a7fa2454ec07e03b03b28550376dc6cdd44edf4fb9d9c98665919', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('eeb1575d-7f0e-1096-b1ba-320942287442', '41020000-0000-0000-0000-000000000003', 3, '2026-10-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2027-01-01"}', NULL,
   '92811263d3f9124b4a1b67210d7170dcffdf47c8fa916d55c88b4974ca3db772', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('9cc6e8e8-734f-09b9-4b81-a215ab1b21bd', '41020000-0000-0000-0000-000000000004', 1, '2026-01-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-05-01"}', NULL,
   'b793c4404f27d9f7ce966858ee74b3b426b502945aee3e154f5a162cdf5b1435', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('54ace11c-eccf-f79e-4a25-59811f97776a', '41020000-0000-0000-0000-000000000004', 2, '2026-05-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "18:00:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-10-01"}', NULL,
   'e131a15c6f2177b85ce3be75051677bd03bc4d1652473d2b491300f433b13067', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('8f5b4ceb-2255-0033-6444-a3e9c75ee91d', '41020000-0000-0000-0000-000000000004', 3, '2026-10-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2027-01-01"}', NULL,
   '29adb6fbb8bb01bdaefe5f870533445871d0e2f8f2f551212b3b2a80e61e87d8', '2026-08-05 四公司标准冬夏令班次初始化', '20000000-0000-0000-0000-000000000001', '2026-08-05 00:00:00.000000'),
  ('e3b0a0c1-ea7f-44c4-b299-d795da36b10f', '5dc79683-0629-49e9-8213-ccde29dfbb8e', 1, '2026-01-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-05-01"}', NULL,
   'd6883b44f64754f4f2f31592aa25b9a6152901f9d01a53570e9bcb4f3a824dfc', '初始化 2026 年考勤默认配置', 'f0ef4828-92c6-48df-9bbc-358336af3de4', '2026-08-03 08:49:16.003503'),
  ('9d60aa97-0a80-4e06-b7e8-f72b18869dc8', '5dc79683-0629-49e9-8213-ccde29dfbb8e', 2, '2026-05-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "18:00:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-10-01"}', NULL,
   '2ea092e637ccb4aebe2f08032879cb6e4c6da1825bb0714986156ab8a3f01140', '初始化 2026 年考勤默认配置', 'f0ef4828-92c6-48df-9bbc-358336af3de4', '2026-08-03 08:49:16.003503'),
  ('3fe27097-9705-4a5f-a176-f7ab6e8e88f6', '5dc79683-0629-49e9-8213-ccde29dfbb8e', 3, '2026-10-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2027-01-01"}', NULL,
   'b1dc409ace5d9fca030dea087e8437ae24989cf120731aed04102b4d0109274d', '初始化 2026 年考勤默认配置', 'f0ef4828-92c6-48df-9bbc-358336af3de4', '2026-08-03 08:49:16.003503'),
  ('6be9d213-f228-5d08-b170-4572bda3ed63', 'da450adf-160d-5a0c-b3a0-0cffbac9da06', 1, '2026-01-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-05-01"}', NULL,
   '2bfe75bae23cb7d3259f2ef9b1e5f44dbd0a7b3be95accc36c2e3551cae59e63', '初始化 2026 年考勤默认配置', '20000000-0000-0000-0000-000000000001', '2026-08-04 00:00:00.000000'),
  ('83e821f3-fcee-5c3d-9795-c9360c8b9017', 'da450adf-160d-5a0c-b3a0-0cffbac9da06', 2, '2026-05-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "18:00:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2026-10-01"}', NULL,
   'b8c2e43bea0da5a4e393e3b85d135de530b72138482a0d21ef576bb642e6e898', '初始化 2026 年考勤默认配置', '20000000-0000-0000-0000-000000000001', '2026-08-04 00:00:00.000000'),
  ('33c98d14-9d9b-50d7-b43d-3efd4838728d', 'da450adf-160d-5a0c-b3a0-0cffbac9da06', 3, '2026-10-01',
   'Asia/Shanghai', '{"segments": [{"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "12:00:00", "startDayOffset": 0, "startLocalTime": "08:30:00"}, {"segmentType": "BREAK", "endDayOffset": 0, "endLocalTime": "13:00:00", "startDayOffset": 0, "startLocalTime": "12:00:00"}, {"segmentType": "WORK", "endDayOffset": 0, "endLocalTime": "17:30:00", "startDayOffset": 0, "startLocalTime": "13:00:00"}], "effectiveTo": "2027-01-01"}', NULL,
   'c823f615a3a0456ffef168bcd9ef5f9b542ef7c352451522762d3dc2b0f3682c', '初始化 2026 年考勤默认配置', '20000000-0000-0000-0000-000000000001', '2026-08-04 00:00:00.000000');
