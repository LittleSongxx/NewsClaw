package vip.newsclaw.datasource.service;

import cn.hutool.crypto.SecureUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.datasource.model.DatasourceEntity;
import vip.newsclaw.datasource.repository.DatasourceMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatasourceServiceCryptoTest {

    private static final String KEY = "TestKey-1234567";
    private DatasourceMapper mapper;
    private DatasourceConnectionManager connections;
    private DatasourceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DatasourceMapper.class);
        connections = mock(DatasourceConnectionManager.class);
        service = new DatasourceService(mapper, connections);
        ReflectionTestUtils.setField(service, "encryptKey", KEY);
    }

    @Test
    void createUsesVersionedGcm() {
        DatasourceEntity entity = datasource("db-password");

        service.create(entity);

        assertTrue(entity.getPassword().startsWith("enc:v1:"));
        assertNotEquals("db-password", entity.getPassword());
        verify(mapper).insert(entity);
    }

    @Test
    void legacyCiphertextDecryptsAndMigratesOnMaskedUpdate() {
        byte[] legacyKey = Arrays.copyOf(KEY.getBytes(StandardCharsets.UTF_8), 16);
        DatasourceEntity existing = datasource(
                SecureUtil.aes(legacyKey).encryptHex("legacy-password"));
        existing.setId(9L);
        when(mapper.selectById(9L)).thenReturn(existing);

        DatasourceEntity update = datasource("******");
        update.setId(9L);
        service.update(update);

        assertTrue(update.getPassword().startsWith("enc:v1:"));
        when(mapper.selectById(9L)).thenReturn(update);
        assertEquals("legacy-password", service.getDecrypted(9L).getPassword());
        verify(mapper).updateById(update);
        verify(connections).invalidate(9L);
    }

    private static DatasourceEntity datasource(String password) {
        DatasourceEntity entity = new DatasourceEntity();
        entity.setName("test-db");
        entity.setPassword(password);
        return entity;
    }
}
