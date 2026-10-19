-- 备份 consult_message（before-v4）  20260929-112603
USE psycare;
DROP TABLE IF EXISTS consult_message;
CREATE TABLE `consult_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `sender` varchar(8) NOT NULL COMMENT 'RESIDENT/DOCTOR/AI',
  `sender_id` bigint DEFAULT NULL,
  `content` text,
  `crisis_flag` tinyint DEFAULT '0',
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问诊消息';
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (1,11,'RESIDENT',6,'我最近总想自杀',1,'2026-09-24 17:15:49');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (2,11,'AI',NULL,'我注意到你可能正经历非常艰难的时刻。请一定照顾好自己。\n如果你此刻有伤害自己的念头，请立即拨打心理援助热线：\n全国 24 小时心理援助热线 12356；北京心理危机研究与干预中心 010-82951332。\n同时我已为你转接人工，稍后会有专业人员与你联系。',1,'2026-09-24 17:15:49');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (3,13,'RESIDENT',1,'最近总是睡不好，该怎么办？',0,'2026-09-24 17:19:51');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (4,18,'RESIDENT',6,'我不想活了，想要结束自己的生命',1,'2026-09-28 10:13:59');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (5,18,'AI',NULL,'我注意到你可能正经历非常艰难的时刻。请一定照顾好自己。\n如果你此刻有伤害自己的念头，请立即拨打心理援助热线：\n全国 24 小时心理援助热线 12356；北京心理危机研究与干预中心 010-82951332。\n同时我已为你转接人工，稍后会有专业人员与你联系。',1,'2026-09-28 10:13:59');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (6,19,'RESIDENT',6,'我不想活了，想要结束自己的生命',1,'2026-09-28 10:14:43');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (7,19,'AI',NULL,'我注意到你可能正经历非常艰难的时刻。请一定照顾好自己。\n如果你此刻有伤害自己的念头，请立即拨打心理援助热线：\n全国 24 小时心理援助热线 12356；北京心理危机研究与干预中心 010-82951332。\n同时我已为你转接人工，稍后会有专业人员与你联系。',1,'2026-09-28 10:14:43');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (8,20,'RESIDENT',6,'我不想活了，想要结束自己的生命',1,'2026-09-28 10:15:07');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (9,20,'AI',NULL,'我注意到你可能正经历非常艰难的时刻。请一定照顾好自己。\n如果你此刻有伤害自己的念头，请立即拨打心理援助热线：\n全国 24 小时心理援助热线 12356；北京心理危机研究与干预中心 010-82951332。\n同时我已为你转接人工，稍后会有专业人员与你联系。',1,'2026-09-28 10:15:07');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (10,26,'RESIDENT',6,'我该怎么跟医生描述我的情况？',0,'2026-09-28 14:45:39');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (11,30,'RESIDENT',7,'11111',0,'2026-09-29 08:44:17');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (12,30,'DOCTOR',2,'222',0,'2026-09-29 09:19:50');
INSERT INTO consult_message (id,session_id,sender,sender_id,content,crisis_flag,created_at) VALUES (13,35,'RESIDENT',7,'有什么办法能缓解紧张情绪？',0,'2026-09-29 10:12:29');
