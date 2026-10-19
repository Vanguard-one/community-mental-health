-- PsyCare 预警工单清空前备份
-- 生成时间: 20260928-162202
-- 恢复方式: 直接在 psycare 库执行本文件

-- ---------- alert_record (7 行) ----------
INSERT INTO alert_record (id, resident_id, assessment_id, alert_type, alert_level, status, handler_id, handle_note, created_at, updated_at) VALUES (1, 6, 1, 'CRISIS', 'D', 'CLOSED', 1, '无需跟进', '2026-09-24 16:28:03', '2026-09-28 10:07:01');
INSERT INTO alert_record (id, resident_id, assessment_id, alert_type, alert_level, status, handler_id, handle_note, created_at, updated_at) VALUES (2, 6, 3, 'CRISIS', 'D', 'REJECTED', 1, 'AI 问诊命中危机词，需人工立即介入', '2026-09-24 17:15:49', '2026-09-28 10:07:25');
INSERT INTO alert_record (id, resident_id, assessment_id, alert_type, alert_level, status, handler_id, handle_note, created_at, updated_at) VALUES (3, 6, 4, 'CRISIS', 'D', 'CLOSED', 2, 'E2E 自动流转 -> CLOSED', '2026-09-28 10:13:59', '2026-09-28 10:14:00');
INSERT INTO alert_record (id, resident_id, assessment_id, alert_type, alert_level, status, handler_id, handle_note, created_at, updated_at) VALUES (4, 6, NULL, 'CRISIS', 'D', 'REJECTED', 2, 'AI 问诊命中危机词，需人工立即介入', '2026-09-28 10:13:59', '2026-09-28 11:03:46');
INSERT INTO alert_record (id, resident_id, assessment_id, alert_type, alert_level, status, handler_id, handle_note, created_at, updated_at) VALUES (5, 6, 5, 'CRISIS', 'D', 'CLOSED', 2, 'E2E 自动流转 -> CLOSED', '2026-09-28 10:14:43', '2026-09-28 10:14:43');
INSERT INTO alert_record (id, resident_id, assessment_id, alert_type, alert_level, status, handler_id, handle_note, created_at, updated_at) VALUES (6, 6, 6, 'CRISIS', 'D', 'CLOSED', 2, 'E2E 自动流转 -> CLOSED', '2026-09-28 10:15:07', '2026-09-28 10:15:08');
INSERT INTO alert_record (id, resident_id, assessment_id, alert_type, alert_level, status, handler_id, handle_note, created_at, updated_at) VALUES (7, 6, 8, 'TREND', 'B', 'CLOSED', 2, '1111', '2026-09-28 11:01:59', '2026-09-28 15:55:39');

-- ---------- alert_status_log (22 行) ----------
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (1, 1, 'PENDING', 'CONFIRMED', 1, NULL, '2026-09-28 10:05:28');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (2, 1, 'CONFIRMED', 'PROCESSING', 1, '上门咨询', '2026-09-28 10:06:00');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (3, 1, 'PROCESSING', 'FOLLOWED', 1, '已回访，进行心理疏导与定期回访安排', '2026-09-28 10:06:37');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (4, 1, 'FOLLOWED', 'CLOSED', 1, '无需跟进', '2026-09-28 10:07:01');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (5, 2, 'PENDING', 'REJECTED', 1, NULL, '2026-09-28 10:07:25');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (6, 3, 'PENDING', 'CONFIRMED', 2, 'E2E 自动流转 -> CONFIRMED', '2026-09-28 10:13:59');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (7, 3, 'CONFIRMED', 'PROCESSING', 2, 'E2E 自动流转 -> PROCESSING', '2026-09-28 10:13:59');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (8, 3, 'PROCESSING', 'FOLLOWED', 2, 'E2E 自动流转 -> FOLLOWED', '2026-09-28 10:14:00');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (9, 3, 'FOLLOWED', 'CLOSED', 2, 'E2E 自动流转 -> CLOSED', '2026-09-28 10:14:00');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (10, 5, 'PENDING', 'CONFIRMED', 2, 'E2E 自动流转 -> CONFIRMED', '2026-09-28 10:14:43');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (11, 5, 'CONFIRMED', 'PROCESSING', 2, 'E2E 自动流转 -> PROCESSING', '2026-09-28 10:14:43');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (12, 5, 'PROCESSING', 'FOLLOWED', 2, 'E2E 自动流转 -> FOLLOWED', '2026-09-28 10:14:43');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (13, 5, 'FOLLOWED', 'CLOSED', 2, 'E2E 自动流转 -> CLOSED', '2026-09-28 10:14:43');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (14, 6, 'PENDING', 'CONFIRMED', 2, 'E2E 自动流转 -> CONFIRMED', '2026-09-28 10:15:07');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (15, 6, 'CONFIRMED', 'PROCESSING', 2, 'E2E 自动流转 -> PROCESSING', '2026-09-28 10:15:07');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (16, 6, 'PROCESSING', 'FOLLOWED', 2, 'E2E 自动流转 -> FOLLOWED', '2026-09-28 10:15:07');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (17, 6, 'FOLLOWED', 'CLOSED', 2, 'E2E 自动流转 -> CLOSED', '2026-09-28 10:15:08');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (18, 7, 'PENDING', 'CONFIRMED', 2, NULL, '2026-09-28 11:03:37');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (19, 4, 'PENDING', 'REJECTED', 2, NULL, '2026-09-28 11:03:46');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (20, 7, 'CONFIRMED', 'PROCESSING', 2, '1111', '2026-09-28 11:03:51');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (21, 7, 'PROCESSING', 'FOLLOWED', 2, '1111', '2026-09-28 11:03:55');
INSERT INTO alert_status_log (id, alert_id, from_status, to_status, operator_id, remark, created_at) VALUES (22, 7, 'FOLLOWED', 'CLOSED', 2, NULL, '2026-09-28 15:55:39');

