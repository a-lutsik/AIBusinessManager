package com.cadence.crm.internal;

import com.cadence.crm.api.NameWords;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Client names reach the LLM unmasked when the database folds letters differently from {@link NameWords} (a {@code C}
 * collation does not lower Cyrillic), and nothing would fail. So the start fails instead.
 */
@Component
class NameMatchingSelfCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(NameMatchingSelfCheck.class);
    private static final List<String> SAMPLES = List.of(
            "Ё", "ЯНА", "Žofie", "მელაძე", "İlham", "ΣΩΣ", "Дми́трий", "Йод", "Мар'яна", "Мар’яна");

    private final DSLContext dsl;
    private final TransactionTemplate tx;

    NameMatchingSelfCheck(DSLContext dsl, PlatformTransactionManager transactions) {
        this.dsl = dsl;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void afterPropertiesSet() {
        tx.executeWithoutResult(status -> {
            for (String sample : SAMPLES) {
                assertAgrees(sample,
                        dsl.select(DSL.field(ClientRepository.KEY_SQL, String.class, DSL.val(sample))).fetchOne(0, String.class));
            }
            assertLetter(dsl.select(DSL.field("{0} ~ '[[:alpha:]]'", Boolean.class, DSL.val("я"))).fetchOne(0, Boolean.class));
        });
        log.info("Client name matching agrees with the database locale: samples={}", SAMPLES.size());
    }

    static void assertAgrees(String sample, String databaseKey) {
        String expected = NameWords.key(sample);
        if (!expected.equals(databaseKey)) {
            throw new IllegalStateException("Database locale breaks client name masking: '" + sample + "' folds to '" + databaseKey
                    + "' in SQL but to '" + expected + "' in Java. Create the database with a UTF-8 locale such as en_US.UTF-8.");
        }
    }

    static void assertLetter(Boolean cyrillicIsAlpha) {
        if (!Boolean.TRUE.equals(cyrillicIsAlpha)) {
            throw new IllegalStateException("Database locale breaks client name masking: 'я' is not [[:alpha:]] in SQL."
                    + " Create the database with a UTF-8 locale such as en_US.UTF-8.");
        }
    }
}
