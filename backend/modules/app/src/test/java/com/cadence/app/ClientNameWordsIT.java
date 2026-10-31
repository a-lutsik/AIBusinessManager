package com.cadence.app;

import com.cadence.crm.api.ClientName;
import com.cadence.crm.api.ClientView;
import com.cadence.crm.api.CrmService;
import com.cadence.crm.api.NameWords;
import com.cadence.platform.tenancy.TenantContext;
import com.cadence.platform.tenant.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CrmService#findByNameWords} against the real schema as the RLS role: whole words and their inflected forms, ignoring case, composition and accents, one tenant only, never an anonymized client, at most the port's limit of rows.
 */
@SpringBootTest
class ClientNameWordsIT extends AbstractTimescaleIT {

    private static final AtomicLong PHONES = new AtomicLong(995_500_000_000L);

    @Autowired
    CrmService crm;
    @Autowired
    TenantDirectory tenants;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenants.upsert(tenantA, "names-a-" + tenantA, "Names A", "Asia/Tbilisi", "GEL", "GE");
        tenants.upsert(tenantB, "names-b-" + tenantB, "Names B", "Asia/Tbilisi", "GEL", "GE");
    }

    private static <T> T as(UUID tenant, Supplier<T> call) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
            return call.get();
        }
    }

    private ClientView client(UUID tenant, String name) {
        return as(tenant, () -> crm.findOrCreate("+" + PHONES.incrementAndGet(), name, "en", false, null));
    }

    /** What the query returns, a prefilter. */
    private List<String> rows(UUID tenant, String... words) {
        return as(tenant, () -> crm.findByNameWords(Set.of(words))).stream().map(ClientName::displayName).toList();
    }

    /** What the masker keeps of those rows for capitalised words: {@link NameWords#matches} decides. */
    private List<String> names(UUID tenant, String... words) {
        return rows(tenant, words).stream()
                .filter(name -> List.of(name.split("[\\s-]+")).stream().anyMatch(word -> List.of(words).stream()
                        .anyMatch(typed -> NameWords.matches(NameWords.key(typed), NameWords.key(word), true))))
                .toList();
    }

    @Test
    void matchesWholeWordsOnly() {
        client(tenantA, "Anna Ivanova");
        client(tenantA, "Anna-Maria Novak");
        client(tenantA, "Joanna Annabel");
        client(tenantA, "Andrew Sand");

        assertThat(names(tenantA, "anna")).containsExactlyInAnyOrder("Anna Ivanova", "Anna-Maria Novak");
        assertThat(names(tenantA, "maria")).containsExactly("Anna-Maria Novak");
        assertThat(names(tenantA, "and", "the")).isEmpty();
        assertThat(names(tenantA, "ann")).isEmpty();
        assertThat(rows(tenantA, "sandwich")).isEmpty();
    }

    @Test
    void ignoresCaseCompositionAndYo() {
        client(tenantA, "Семён Зайцев");
        client(tenantA, "СЕМЕН ВОЛКОВ");
        client(tenantA, "José Garcia");
        client(tenantA, "გიორგი მელაძე");

        assertThat(names(tenantA, "семен")).containsExactlyInAnyOrder("Семён Зайцев", "СЕМЕН ВОЛКОВ");
        assertThat(names(tenantA, "СЕМЁН")).containsExactlyInAnyOrder("Семён Зайцев", "СЕМЕН ВОЛКОВ");
        assertThat(names(tenantA, "josé")).containsExactly("José Garcia");
        assertThat(names(tenantA, "JOSÉ")).containsExactly("José Garcia");
        assertThat(names(tenantA, "მელაძე")).containsExactly("გიორგი მელაძე");
    }

    @Test
    void findsAClientFromInflectedFormsOfTheName() {
        client(tenantA, "Анна Петрова");
        client(tenantA, "Мария Сидорова");
        client(tenantA, "Иван Кузнецов");
        client(tenantA, "Jana Nováková");
        client(tenantA, "Анкета Рода");

        assertThat(names(tenantA, "анну")).containsExactly("Анна Петрова");
        assertThat(names(tenantA, "Анной")).containsExactly("Анна Петрова");
        assertThat(names(tenantA, "петрову", "петровой")).containsExactly("Анна Петрова");
        assertThat(names(tenantA, "марии")).containsExactly("Мария Сидорова");
        assertThat(names(tenantA, "ивану")).containsExactly("Иван Кузнецов");
        assertThat(names(tenantA, "иваном")).containsExactly("Иван Кузнецов");
        assertThat(names(tenantA, "novákové")).containsExactly("Jana Nováková");
        assertThat(names(tenantA, "novakovou")).containsExactly("Jana Nováková");
        assertThat(names(tenantA, "анна")).containsExactly("Анна Петрова");
    }

    /**
     * The query is never narrower than {@link NameWords#matches}, typed capitalised or not, and among its rows the rule picks
     * exactly what it picks from the stored names; it stays a real filter for the false stems.
     */
    @Test
    void theQueryIsNeverNarrowerThanTheInMemoryRule() {
        List<String> stored = List.of("Иван", "Анна", "Ли", "Петрова", "Павел", "Мария", "Вера", "Ива", "Оля", "Ева", "Яна", "Лена",
                "Mark", "Завтра", "Дана", "ანა", "Мар'яна", "Шевченко", "Игорь", "Nováková", "Стоцкий", "Толстой", "Novotný", "Jiří",
                "Петро", "Сергій", "გიორგი", "დავითი");
        stored.forEach(name -> client(tenantA, name));
        for (String typed : List.of("Ивану", "Иваном", "Ива", "Анну", "Анн", "Ли", "Лия", "Петровой", "Петровым", "Павла", "Марта",
                "верю", "Иванами", "Иваново", "Олю", "олю", "Юлей", "Еву", "Евой", "Яну", "ანას", "Мар'яну", "мар’яну", "Шевченку",
                "Игоря", "Игорем", "Novákové", "Novakovou", "Лену", "Шевченком", "Шевченкові", "Стоцким", "Толстого", "Толстому",
                "Novotným", "Jiřího", "Jiřím", "Петром", "Сергієві", "ანასთან", "გიორგისგან", "დავითისთვის")) {
            for (boolean capitalised : List.of(true, false)) {
                List<String> expected = stored.stream()
                        .filter(name -> NameWords.matches(NameWords.key(typed), NameWords.key(name), capitalised))
                        .toList();
                List<String> found = rows(tenantA, NameWords.key(typed));
                assertThat(found).as(typed + " capitalised=" + capitalised).containsAll(expected);
                assertThat(found.stream()
                        .filter(name -> NameWords.matches(NameWords.key(typed), NameWords.key(name), capitalised))
                        .toList()).as(typed).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
        for (String falseStem : List.of("верно", "лень", "market", "завтрак", "дано")) {
            assertThat(rows(tenantA, falseStem)).as(falseStem).isEmpty();
        }
    }

    @Test
    void findsShortNamesAndNamesWithAnApostrophe() {
        client(tenantA, "Оля Петрова");
        client(tenantA, "Мар'яна Коваль");
        client(tenantA, "ანა მელაძე");

        assertThat(names(tenantA, "олю")).containsExactly("Оля Петрова");
        assertThat(names(tenantA, "мар'яну")).containsExactly("Мар'яна Коваль");
        assertThat(names(tenantA, "мар’яной")).containsExactly("Мар'яна Коваль");
        assertThat(names(tenantA, "ანას")).containsExactly("ანა მელაძე");
    }

    /** m3: Java and the database fold these the same way, whichever side has which form. */
    @Test
    void foldsDottedISigmaAndCombiningAccentsLikeTheJavaSide() {
        client(tenantA, "İlham Aliyev");
        client(tenantA, "ΣΩΣ Papas");
        client(tenantA, "Дми\u0301трий Орлов");
        client(tenantA, "Анна-Мари\u0301я Лисова");

        assertThat(names(tenantA, "ilham")).containsExactly("İlham Aliyev");
        assertThat(names(tenantA, "İLHAM")).containsExactly("İlham Aliyev");
        assertThat(names(tenantA, "ılham")).containsExactly("İlham Aliyev");
        assertThat(names(tenantA, "σως")).containsExactly("ΣΩΣ Papas");
        assertThat(names(tenantA, "ΣΩΣ")).containsExactly("ΣΩΣ Papas");
        assertThat(names(tenantA, "дмитрий")).containsExactly("Дми\u0301трий Орлов");
        assertThat(names(tenantA, "дми\u0301трию")).containsExactly("Дми\u0301трий Орлов");
        assertThat(names(tenantA, "марию")).containsExactly("Анна-Мари\u0301я Лисова");
    }

    @Test
    void shortNamesAndSeveralWordsInOneQuery() {
        client(tenantA, "Ли Ян");
        client(tenantA, "Maria Sokolova");

        assertThat(names(tenantA, "ли", "sokolova")).containsExactlyInAnyOrder("Ли Ян", "Maria Sokolova");
        assertThat(as(tenantA, () -> crm.findByNameWords(Set.of()))).isEmpty();
    }

    @Test
    void anotherTenantSeesNothing() {
        client(tenantA, "Tenantwide Anna");

        assertThat(names(tenantA, "tenantwide")).hasSize(1);
        assertThat(rows(tenantB, "tenantwide")).isEmpty();
    }

    @Test
    void anonymizedClientsAreNeverReturned() {
        ClientView gone = client(tenantA, "Forgotten Person");
        as(tenantA, () -> crm.anonymize(gone.id()));

        assertThat(rows(tenantA, "forgotten")).isEmpty();
    }

    @Test
    void returnsAtMostTheLimit() {
        for (int i = 0; i <= CrmService.NAME_WORD_MATCH_LIMIT; i++) {
            client(tenantA, "Crowded " + i);
        }

        assertThat(rows(tenantA, "crowded")).hasSize(CrmService.NAME_WORD_MATCH_LIMIT);
    }
}
