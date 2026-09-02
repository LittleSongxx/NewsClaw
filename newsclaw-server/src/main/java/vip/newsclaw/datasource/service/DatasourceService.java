package vip.newsclaw.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.newsclaw.common.crypto.VersionedAesGcmCrypto;
import vip.newsclaw.datasource.model.DatasourceEntity;
import vip.newsclaw.datasource.repository.DatasourceMapper;
import vip.newsclaw.exception.NewsClawException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据源业务服务
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceService {

    private static final String CRYPTO_PURPOSE = "datasource-password";
    private static final Pattern LEGACY_HEX = Pattern.compile("(?i)^[0-9a-f]+$");

    private final DatasourceMapper datasourceMapper;
    private final DatasourceConnectionManager connectionManager;

    @Value("${newsclaw.datasource.encrypt-key:}")
    private String encryptKey;

    // ==================== CRUD ====================

    public List<DatasourceEntity> listAll() {
        List<DatasourceEntity> list = datasourceMapper.selectList(
                new LambdaQueryWrapper<DatasourceEntity>().orderByDesc(DatasourceEntity::getCreateTime));
        list.forEach(this::maskPassword);
        return list;
    }

    public List<DatasourceEntity> listEnabled() {
        return datasourceMapper.selectList(
                new LambdaQueryWrapper<DatasourceEntity>()
                        .eq(DatasourceEntity::getEnabled, true)
                        .orderByAsc(DatasourceEntity::getName));
    }

    public DatasourceEntity getById(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        if (entity == null) {
            throw new NewsClawException("err.datasource.not_found", "数据源不存在: " + id);
        }
        return entity;
    }

    public DatasourceEntity getByIdMasked(Long id) {
        DatasourceEntity entity = getById(id);
        maskPassword(entity);
        return entity;
    }

    public DatasourceEntity create(DatasourceEntity entity) {
        if (entity == null) throw new NewsClawException(400, "datasource is required");
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        encryptPassword(entity);
        datasourceMapper.insert(entity);
        return entity;
    }

    public DatasourceEntity update(DatasourceEntity entity) {
        if (entity == null || entity.getId() == null) {
            throw new NewsClawException(400, "datasource id is required");
        }
        DatasourceEntity existing = getById(entity.getId());
        // 如果前端传回的密码是脱敏值，保留原密码
        if ("******".equals(entity.getPassword()) || entity.getPassword() == null) {
            migratePasswordEncryption(existing);
            entity.setPassword(existing.getPassword());
        } else {
            encryptPassword(entity);
        }
        datasourceMapper.updateById(entity);
        // 失效连接池缓存
        connectionManager.invalidate(entity.getId());
        return entity;
    }

    public void delete(Long id) {
        datasourceMapper.deleteById(id);
        connectionManager.invalidate(id);
    }

    public DatasourceEntity toggle(Long id, boolean enabled) {
        DatasourceEntity entity = getById(id);
        entity.setEnabled(enabled);
        migratePasswordEncryption(entity);
        datasourceMapper.updateById(entity);
        if (!enabled) {
            connectionManager.invalidate(id);
        }
        return entity;
    }

    // ==================== 连接测试 ====================

    public boolean testConnection(Long id) {
        DatasourceEntity entity = getById(id);
        decryptPassword(entity);
        boolean ok;
        try {
            ok = connectionManager.testConnection(entity);
        } finally {
            // Never leave a decrypted password on an entity returned from the
            // mapper, even when the driver throws before the normal result path.
            encryptPassword(entity);
        }
        // 更新测试结果
        entity.setLastTestTime(LocalDateTime.now());
        entity.setLastTestOk(ok);
        datasourceMapper.updateById(entity);
        return ok;
    }

    // ==================== 内部方法供 Tool 使用 ====================

    /**
     * 获取解密密码后的实体（供 Tool 层获取连接用）
     */
    public DatasourceEntity getDecrypted(Long id) {
        DatasourceEntity entity = getById(id);
        decryptPassword(entity);
        return entity;
    }

    // ==================== 加解密 ====================

    private VersionedAesGcmCrypto crypto() {
        return new VersionedAesGcmCrypto(encryptKey, CRYPTO_PURPOSE);
    }

    private void encryptPassword(DatasourceEntity entity) {
        if (entity.getPassword() != null && !entity.getPassword().isBlank()) {
            entity.setPassword(crypto().encrypt(entity.getPassword()));
        }
    }

    private void decryptPassword(DatasourceEntity entity) {
        String stored = entity.getPassword();
        if (stored == null || stored.isBlank()) return;
        if (!VersionedAesGcmCrypto.isVersioned(stored) && !looksLikeLegacyCiphertext(stored)) {
            return; // oldest installations may still contain plaintext
        }
        try {
            entity.setPassword(crypto().decrypt(stored));
        } catch (Exception e) {
            log.error("Datasource password authentication/decryption failed for datasource {}", entity.getId());
            throw new NewsClawException("err.datasource.decrypt_failed", 500,
                    "Datasource password could not be decrypted; verify the encryption key");
        }
    }

    /** Upgrade legacy ECB/plaintext storage whenever an otherwise normal write occurs. */
    private void migratePasswordEncryption(DatasourceEntity entity) {
        String stored = entity.getPassword();
        if (stored == null || stored.isBlank() || VersionedAesGcmCrypto.isVersioned(stored)) return;
        decryptPassword(entity);
        encryptPassword(entity);
    }

    private static boolean looksLikeLegacyCiphertext(String stored) {
        return stored.length() >= 32 && stored.length() % 32 == 0
                && LEGACY_HEX.matcher(stored).matches();
    }

    private void maskPassword(DatasourceEntity entity) {
        if (entity.getPassword() != null && !entity.getPassword().isBlank()) {
            entity.setPassword("******");
        }
    }
}
