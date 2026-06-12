-- 重置管理端账号为 admin / admin123（与 Java Md5Util 一致）
USE `ai-call`;

INSERT INTO `admin_user` (`username`, `password`)
VALUES ('admin', '0192023a7bbd73250516f069df18b500')
ON DUPLICATE KEY UPDATE `password` = '0192023a7bbd73250516f069df18b500';
