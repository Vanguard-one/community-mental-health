-- =============================================================
-- v6 演示数据：20 位医生（科室从 3 个扩到 10 个）+ 40 位居民
-- =============================================================
--
-- 【为什么需要这个脚本】
--   真库里只有 4 位可咨询医生、3 个科室、3 位居民，
--   导致「医生咨询」页科室卡只有 3 个、医生列表每科 1~2 人，
--   页面上大片留白，演示时看不出产品形态。
--
-- 【前置条件（重要）】
--   本脚本依赖下面两个账号存在，用来复制 BCrypt 密码哈希：
--     doctor_li  → 新医生密码 = doctor123
--     user01     → 新居民密码 = user123
--   BCrypt 是不可逆哈希，**不能也不该硬编码进 SQL**（项目约定：
--   DataInitializer 的注释里写明了「密码用 BCrypt 实时生成，不在 SQL 里硬编码」）。
--   所以这里用 SELECT 从种子账号搬运哈希 —— 搬运的是可校验的密文，不是明文。
--   若这两个账号不存在（全新库还没启动过后端），请先启动一次后端再执行本脚本。
--
-- 【幂等】
--   全部插入都是「LEFT JOIN 目标表 ... WHERE 目标表.id IS NULL」，
--   重复执行只会跳过已存在的行，不会产生重复账号，也不会改动已有数据。
--
-- 【回滚】
--   本脚本只新增行、不修改既有行。回滚 = 删掉本次新增的账号：
--     DELETE FROM sys_user WHERE id > 11;   -- id <= 11 是脚本执行前的存量
--   执行前的快照表：sys_user_bak_20260929 / doctor_bak_20260929 / user_bak_20260929
--
-- 【科室设计】
--   存量 3 个科室（全科 / 心理科 / 精神科）保留不动，新增 7 个贴合社区场景的科室：
--   睡眠医学科、儿童青少年心理科、老年心理科、心身医学科、危机干预门诊、康复科、中医心理科。
--   10 个科室合计 24 位医生，每个科室至少 1 人 —— 保证没有空科室（点进去空空如也更难看）。
-- =============================================================

USE psycare;

SET @doc_pwd = (SELECT password FROM sys_user WHERE username = 'doctor_li' AND is_deleted = 0 LIMIT 1);
SET @usr_pwd = (SELECT password FROM sys_user WHERE username = 'user01'   AND is_deleted = 0 LIMIT 1);

-- =============================================================
-- 一、20 位医生
-- =============================================================

DROP TEMPORARY TABLE IF EXISTS `_seed_doctor`;
CREATE TEMPORARY TABLE `_seed_doctor` (
  username       VARCHAR(64)  NOT NULL PRIMARY KEY,
  real_name      VARCHAR(64),
  phone          VARCHAR(32),
  license_no     VARCHAR(64),
  department     VARCHAR(32),
  title          VARCHAR(32),
  expertise_tags VARCHAR(255),
  rating         DOUBLE,
  current_load   INT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO `_seed_doctor`
  (username, real_name, phone, license_no, department, title, expertise_tags, rating, current_load)
VALUES
  ('doctor_sun',   '孙医生', '13700001001', '110101000012', '全科',             '副主任医师', '高血压,糖尿病,情绪低落',        4.4, 3),
  ('doctor_zhou',  '周医生', '13700001002', '110101000013', '心理科',           '主治医师',   '焦虑,压力管理,婚姻家庭',        4.7, 2),
  ('doctor_wu',    '吴医生', '13700001003', '110101000014', '心理科',           '心理治疗师', '抑郁,人际关系,自我成长',        4.5, 1),
  ('doctor_zheng', '郑医生', '13700001004', '110101000015', '精神科',           '主任医师',   '双相障碍,精神分裂,药物调整',    4.9, 4),
  ('doctor_feng',  '冯医生', '13700001005', '110101000016', '精神科',           '副主任医师', '抑郁,强迫,焦虑',                4.6, 2),
  ('doctor_chen',  '陈医生', '13700001006', '110101000017', '睡眠医学科',       '副主任医师', '失眠,睡眠呼吸暂停,昼夜节律',    4.8, 5),
  ('doctor_jiang', '蒋医生', '13700001007', '110101000018', '睡眠医学科',       '主治医师',   '入睡困难,早醒,梦魇',            4.4, 2),
  ('doctor_shen',  '沈医生', '13700001008', '110101000019', '睡眠医学科',       '心理治疗师', '失眠认知行为治疗,放松训练',     4.6, 1),
  ('doctor_han',   '韩医生', '13700001009', '110101000020', '儿童青少年心理科', '副主任医师', '青少年抑郁,亲子沟通,学习压力',  4.8, 3),
  ('doctor_yang',  '杨医生', '13700001010', '110101000021', '儿童青少年心理科', '主治医师',   '注意力缺陷,考试焦虑,网络成瘾',  4.5, 2),
  ('doctor_zhu',   '朱医生', '13700001011', '110101000022', '儿童青少年心理科', '心理治疗师', '儿童情绪,家庭治疗,沙盘治疗',    4.7, 0),
  ('doctor_qin',   '秦医生', '13700001012', '110101000023', '老年心理科',       '主任医师',   '老年抑郁,认知障碍,孤独感',      4.9, 2),
  ('doctor_xu',    '许医生', '13700001013', '110101000024', '老年心理科',       '主治医师',   '更年期,丧偶哀伤,慢病共病',      4.5, 1),
  ('doctor_he',    '何医生', '13700001014', '110101000025', '心身医学科',       '副主任医师', '躯体化,慢性疼痛,功能性胃肠',    4.6, 2),
  ('doctor_lv',    '吕医生', '13700001015', '110101000026', '心身医学科',       '主治医师',   '压力相关,心悸,失眠',            4.4, 1),
  ('doctor_zhang', '张医生', '13700001016', '110101000027', '危机干预门诊',     '主任医师',   '自伤,创伤,急性应激',            4.9, 1),
  ('doctor_kong',  '孔医生', '13700001017', '110101000028', '危机干预门诊',     '副主任医师', '危机干预,哀伤辅导,热线转介',    4.7, 0),
  ('doctor_cao',   '曹医生', '13700001018', '110101000029', '康复科',           '副主任医师', '精神康复,社会功能,职业重建',    4.5, 2),
  ('doctor_yan',   '严医生', '13700001019', '110101000030', '康复科',           '心理治疗师', '团体治疗,社交训练,复发预防',    4.6, 1),
  ('doctor_hua',   '华医生', '13700001020', '110101000031', '中医心理科',       '主任医师',   '中医神志病,失眠,郁证',          4.7, 2);

-- 1.1 账号（sys_user）—— 已存在的 username 会被 LEFT JOIN 排除掉
INSERT INTO sys_user
  (username, password, real_name, phone, role, status, is_deleted, created_at, updated_at)
SELECT d.username, @doc_pwd, d.real_name, d.phone, 'DOCTOR', 1, 0, NOW(), NOW()
FROM `_seed_doctor` d
         LEFT JOIN sys_user u ON u.username = d.username AND u.is_deleted = 0
WHERE u.id IS NULL;

-- 1.2 医生档案（doctor）—— id 取自上一步的 sys_user.id
--     audit_status 直接给 APPROVED：这是演示数据，不该卡在待审核导致登录被拒。
INSERT INTO doctor
  (id, license_no, department, title, expertise_tags, rating, current_load,
   available, audit_status, audit_remark, audited_by, audited_at, created_at, updated_at)
SELECT u.id, d.license_no, d.department, d.title, d.expertise_tags, d.rating, d.current_load,
       1, 'APPROVED', '演示数据（v6 脚本）', 1, NOW(), NOW(), NOW()
FROM `_seed_doctor` d
         JOIN sys_user u ON u.username = d.username AND u.is_deleted = 0
         LEFT JOIN doctor x ON x.id = u.id
WHERE x.id IS NULL;

-- =============================================================
-- 二、40 位居民
-- =============================================================

DROP TEMPORARY TABLE IF EXISTS `_seed_user`;
CREATE TEMPORARY TABLE `_seed_user` (
  username          VARCHAR(64) NOT NULL PRIMARY KEY,
  real_name         VARCHAR(64),
  phone             VARCHAR(32),
  gender            VARCHAR(8),
  birth_year        INT,
  occupation        VARCHAR(64),
  emergency_contact VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO `_seed_user`
  (username, real_name, phone, gender, birth_year, occupation, emergency_contact)
VALUES
  ('user03', '王芳', '13900001003', 'F', 1992, '教师',     '王先生'),
  ('user04', '李伟', '13900001004', 'M', 1988, '软件工程师', '李女士'),
  ('user05', '张敏', '13900001005', 'F', 1995, '会计',     '张先生'),
  ('user06', '刘洋', '13900001006', 'M', 1990, '销售',     '刘女士'),
  ('user07', '陈静', '13900001007', 'F', 1998, '护士',     '陈先生'),
  ('user08', '杨帆', '13900001008', 'M', 1985, '司机',     '杨女士'),
  ('user09', '赵磊', '13900001009', 'M', 1993, '工程师',   '赵女士'),
  ('user10', '黄丽', '13900001010', 'F', 1996, '教师',     '黄先生'),
  ('user11', '周涛', '13900001011', 'M', 1987, '个体经营', '周女士'),
  ('user12', '吴娟', '13900001012', 'F', 1991, '公务员',   '吴先生'),
  ('user13', '徐强', '13900001013', 'M', 1994, '配送员',   '徐女士'),
  ('user14', '孙娜', '13900001014', 'F', 1999, '学生',     '孙先生'),
  ('user15', '马超', '13900001015', 'M', 1983, '保安',     '马女士'),
  ('user16', '朱婷', '13900001016', 'F', 1997, '护士',     '朱先生'),
  ('user17', '胡军', '13900001017', 'M', 1986, '建筑工人', '胡女士'),
  ('user18', '郭燕', '13900001018', 'F', 1993, '财务',     '郭先生'),
  ('user19', '何勇', '13900001019', 'M', 1989, '厨师',     '何女士'),
  ('user20', '高娜', '13900001020', 'F', 2000, '学生',     '高先生'),
  ('user21', '林峰', '13900001021', 'M', 1981, '自由职业', '林女士'),
  ('user22', '罗静', '13900001022', 'F', 1994, '文员',     '罗先生'),
  ('user23', '郑伟', '13900001023', 'M', 1992, '电工',     '郑女士'),
  ('user24', '梁敏', '13900001024', 'F', 1990, '教师',     '梁先生'),
  ('user25', '谢军', '13900001025', 'M', 1978, '企业管理', '谢女士'),
  ('user26', '宋涛', '13900001026', 'M', 1996, '健身教练', '宋女士'),
  ('user27', '唐丽', '13900001027', 'F', 1988, '客服',     '唐先生'),
  ('user28', '许强', '13900001028', 'M', 1975, '退休',     '许女士'),
  ('user29', '韩雪', '13900001029', 'F', 2001, '学生',     '韩先生'),
  ('user30', '冯刚', '13900001030', 'M', 1984, '车间主管', '冯女士'),
  ('user31', '邓丽', '13900001031', 'F', 1991, '护士',     '邓先生'),
  ('user32', '曹伟', '13900001032', 'M', 1997, '程序员',   '曹女士'),
  ('user33', '彭勇', '13900001033', 'M', 1980, '司机',     '彭女士'),
  ('user34', '曾敏', '13900001034', 'F', 1996, '幼儿教师', '曾先生'),
  ('user35', '肖峰', '13900001035', 'M', 1993, '摄影师',   '肖女士'),
  ('user36', '田静', '13900001036', 'F', 1989, '药师',     '田先生'),
  ('user37', '董伟', '13900001037', 'M', 1987, '采购',     '董女士'),
  ('user38', '潘涛', '13900001038', 'M', 1995, '厨师',     '潘女士'),
  ('user39', '袁丽', '13900001039', 'F', 1992, '保洁员',   '袁先生'),
  ('user40', '蒋军', '13900001040', 'M', 1986, '维修工',   '蒋女士'),
  ('user41', '蔡敏', '13900001041', 'F', 1998, '收银员',   '蔡先生'),
  ('user42', '余强', '13900001042', 'M', 1984, '司机',     '余女士');

-- 2.1 账号
INSERT INTO sys_user
  (username, password, real_name, phone, role, status, is_deleted, created_at, updated_at)
SELECT s.username, @usr_pwd, s.real_name, s.phone, 'RESIDENT', 1, 0, NOW(), NOW()
FROM `_seed_user` s
         LEFT JOIN sys_user u ON u.username = s.username AND u.is_deleted = 0
WHERE u.id IS NULL;

-- 2.2 居民档案（`user`）—— 注意表名是保留字，必须加反引号
INSERT INTO `user` (id, gender, birth_year, occupation, emergency_contact, created_at, updated_at)
SELECT u.id, s.gender, s.birth_year, s.occupation, s.emergency_contact, NOW(), NOW()
FROM `_seed_user` s
         JOIN sys_user u ON u.username = s.username AND u.is_deleted = 0
         LEFT JOIN `user` y ON y.id = u.id
WHERE y.id IS NULL;

DROP TEMPORARY TABLE IF EXISTS `_seed_doctor`;
DROP TEMPORARY TABLE IF EXISTS `_seed_user`;

-- =============================================================
-- 三、验证（执行完可直接跑这几条看结果）
-- =============================================================

-- 3.1 账号总数：应 ≥ 1 管理员 + 24 医生 + 43 居民
SELECT role, COUNT(*) AS cnt
FROM sys_user
WHERE is_deleted = 0
GROUP BY role;

-- 3.2 科室分布：应为 10 个科室，每个至少 1 人，合计 24
SELECT d.department                               AS 科室,
       COUNT(*)                                   AS 医生数,
       ROUND(AVG(d.rating), 2)                    AS 平均评分,
       SUM(d.available = 1 AND d.audit_status = 'APPROVED') AS 可咨询
FROM doctor d
         JOIN sys_user u ON u.id = d.id AND u.is_deleted = 0 AND u.status = 1
WHERE d.audit_status = 'APPROVED'
GROUP BY d.department
ORDER BY 医生数 DESC, d.department;

-- 3.3 居民档案完整性：应为 43 行，且新增的 40 行都有 gender/occupation
SELECT COUNT(*) AS 居民档案数,
       SUM(gender IS NOT NULL)      AS 有性别,
       SUM(occupation IS NOT NULL)  AS 有职业
FROM `user`;
