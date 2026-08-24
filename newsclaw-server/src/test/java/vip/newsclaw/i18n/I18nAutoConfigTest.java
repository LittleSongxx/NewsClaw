package vip.newsclaw.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.newsclaw.common.result.R;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class I18nAutoConfigTest {

    @AfterEach
    void clearStaticHolder() {
        R.setI18n(null);
    }

    @Test
    void closingAnOldContextDoesNotClearANewerRegistration() {
        I18nService first = mock(I18nService.class);
        I18nService second = mock(I18nService.class);
        when(first.msg("result.success")).thenReturn("first");
        when(second.msg("result.success")).thenReturn("second");

        I18nAutoConfig firstContext = new I18nAutoConfig(first);
        I18nAutoConfig secondContext = new I18nAutoConfig(second);

        firstContext.init();
        assertEquals("first", R.ok().getMsg());

        secondContext.init();
        firstContext.destroy();
        assertEquals("second", R.ok().getMsg());

        secondContext.destroy();
        assertEquals("result.success", R.ok().getMsg());
    }
}
