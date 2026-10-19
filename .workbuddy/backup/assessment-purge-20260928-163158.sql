-- PsyCare 测评记录清空前备份
-- 生成时间: 20260928-163158
-- 恢复: 先执行下面 INSERT，再执行文件末尾的 UPDATE 还原 consult_session 引用

-- ---------- assessment (8 行) ----------
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (1, 6, 1, 14, '中度', 'D', 1, '{\"101\":2,\"102\":8,\"103\":9,\"104\":15,\"105\":18,\"106\":21,\"107\":27,\"108\":31,\"109\":35}', '2026-09-24 16:28:02');
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (2, 6, 1, 9, '轻度', 'D', 1, '{\"101\":3,\"102\":7,\"103\":11,\"104\":15,\"105\":19,\"106\":23,\"107\":27,\"108\":31,\"109\":35}', '2026-09-24 16:48:09');
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (3, 6, 1, 8, '轻度', 'A', 0, '{\"101\":3,\"102\":7,\"103\":11,\"104\":15,\"105\":19,\"106\":23,\"107\":27,\"108\":31,\"109\":36}', '2026-09-24 16:48:38');
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (4, 6, 1, 3, '无', 'D', 1, '{\"101\":4,\"102\":8,\"103\":12,\"104\":16,\"105\":20,\"106\":24,\"107\":28,\"108\":32,\"109\":33}', '2026-09-28 10:13:59');
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (5, 6, 1, 3, '无', 'D', 1, '{\"101\":4,\"102\":8,\"103\":12,\"104\":16,\"105\":20,\"106\":24,\"107\":28,\"108\":32,\"109\":33}', '2026-09-28 10:14:43');
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (6, 6, 1, 3, '无', 'D', 1, '{\"101\":4,\"102\":8,\"103\":12,\"104\":16,\"105\":20,\"106\":24,\"107\":28,\"108\":32,\"109\":33}', '2026-09-28 10:15:07');
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (7, 6, 2, 0, '极轻', 'A', 0, '{\"201\":40,\"202\":44,\"203\":48,\"204\":52,\"205\":56,\"206\":60,\"207\":64}', '2026-09-28 11:01:37');
INSERT INTO assessment (id, resident_id, scale_id, total_score, raw_level, risk_level, crisis_flag, answer_json, created_at) VALUES (8, 6, 2, 5, '轻度', 'A', 0, '{\"201\":39,\"202\":44,\"203\":47,\"204\":52,\"205\":55,\"206\":58,\"207\":64}', '2026-09-28 11:01:59');

-- ---------- assessment_answer (68 行) ----------
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (1, 1, 101, NULL, NULL, 2);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (2, 1, 102, NULL, NULL, 8);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (3, 1, 103, NULL, NULL, 9);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (4, 1, 104, NULL, NULL, 15);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (5, 1, 105, NULL, NULL, 18);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (6, 1, 106, NULL, NULL, 21);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (7, 1, 107, NULL, NULL, 27);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (8, 1, 108, NULL, NULL, 31);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (9, 1, 109, NULL, NULL, 35);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (10, 2, 101, NULL, NULL, 3);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (11, 2, 102, NULL, NULL, 7);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (12, 2, 103, NULL, NULL, 11);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (13, 2, 104, NULL, NULL, 15);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (14, 2, 105, NULL, NULL, 19);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (15, 2, 106, NULL, NULL, 23);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (16, 2, 107, NULL, NULL, 27);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (17, 2, 108, NULL, NULL, 31);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (18, 2, 109, NULL, NULL, 35);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (19, 3, 101, NULL, NULL, 3);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (20, 3, 102, NULL, NULL, 7);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (21, 3, 103, NULL, NULL, 11);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (22, 3, 104, NULL, NULL, 15);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (23, 3, 105, NULL, NULL, 19);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (24, 3, 106, NULL, NULL, 23);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (25, 3, 107, NULL, NULL, 27);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (26, 3, 108, NULL, NULL, 31);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (27, 3, 109, NULL, NULL, 36);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (28, 4, 101, NULL, NULL, 4);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (29, 4, 102, NULL, NULL, 8);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (30, 4, 103, NULL, NULL, 12);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (31, 4, 104, NULL, NULL, 16);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (32, 4, 105, NULL, NULL, 20);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (33, 4, 106, NULL, NULL, 24);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (34, 4, 107, NULL, NULL, 28);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (35, 4, 108, NULL, NULL, 32);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (36, 4, 109, NULL, NULL, 33);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (37, 5, 101, NULL, NULL, 4);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (38, 5, 102, NULL, NULL, 8);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (39, 5, 103, NULL, NULL, 12);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (40, 5, 104, NULL, NULL, 16);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (41, 5, 105, NULL, NULL, 20);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (42, 5, 106, NULL, NULL, 24);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (43, 5, 107, NULL, NULL, 28);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (44, 5, 108, NULL, NULL, 32);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (45, 5, 109, NULL, NULL, 33);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (46, 6, 101, NULL, NULL, 4);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (47, 6, 102, NULL, NULL, 8);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (48, 6, 103, NULL, NULL, 12);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (49, 6, 104, NULL, NULL, 16);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (50, 6, 105, NULL, NULL, 20);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (51, 6, 106, NULL, NULL, 24);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (52, 6, 107, NULL, NULL, 28);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (53, 6, 108, NULL, NULL, 32);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (54, 6, 109, NULL, NULL, 33);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (55, 7, 201, NULL, NULL, 40);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (56, 7, 202, NULL, NULL, 44);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (57, 7, 203, NULL, NULL, 48);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (58, 7, 204, NULL, NULL, 52);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (59, 7, 205, NULL, NULL, 56);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (60, 7, 206, NULL, NULL, 60);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (61, 7, 207, NULL, NULL, 64);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (62, 8, 201, NULL, NULL, 39);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (63, 8, 202, NULL, NULL, 44);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (64, 8, 203, NULL, NULL, 47);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (65, 8, 204, NULL, NULL, 52);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (66, 8, 205, NULL, NULL, 55);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (67, 8, 206, NULL, NULL, 58);
INSERT INTO assessment_answer (id, assessment_id, item_id, item_no, score, option_id) VALUES (68, 8, 207, NULL, NULL, 64);

-- ---------- consult_session 引用还原 (6 行) ----------
UPDATE consult_session SET assessment_id = 3 WHERE id = 10;
UPDATE consult_session SET assessment_id = 3 WHERE id = 11;
UPDATE consult_session SET assessment_id = 2 WHERE id = 12;
UPDATE consult_session SET assessment_id = 2 WHERE id = 15;
UPDATE consult_session SET assessment_id = 5 WHERE id = 19;
UPDATE consult_session SET assessment_id = 6 WHERE id = 20;
