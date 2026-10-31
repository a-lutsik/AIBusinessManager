package com.cadence.ai.internal.privacy;

import com.cadence.ai.internal.privacy.OwnerMessageMasker.Person;
import com.cadence.crm.api.CrmService;
import com.cadence.crm.api.NameWords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerMessageMaskerTest {

    /** Like {@code CrmService.findByNameWords}: words of the display name by {@link NameWords#matches}, at most the port's limit of rows. */
    static final class FakeClients implements OwnerMessageMasker.Clients {
        final List<Person> people = new ArrayList<>();
        final List<Set<String>> queries = new ArrayList<>();

        Person add(String name) {
            Person person = new Person(UUID.randomUUID(), name);
            people.add(person);
            return person;
        }

        @Override
        public List<Person> findByNameWords(Set<String> words) {
            queries.add(words);
            return people.stream()
                    .filter(p -> Words.nameWords(p.displayName()).stream()
                            .anyMatch(n -> words.stream().anyMatch(w -> NameWords.matches(w, n, true))))
                    .limit(CrmService.NAME_WORD_MATCH_LIMIT)
                    .toList();
        }
    }

    private final UUID convo = UUID.randomUUID();
    private final List<Person> masters = new ArrayList<>();
    private FakeClients clients;
    private PseudonymRegistry registry;
    private OwnerMessageMasker masker;

    private Person annaIvanova;
    private Person annaPetrova;
    private Person maria;
    private Person ninoMaster;
    private Person ninoClient;

    @BeforeEach
    void setUp() {
        clients = new FakeClients();
        registry = new PseudonymRegistry(new InMemoryPseudonymStore());
        masker = new OwnerMessageMasker(() -> masters, clients, registry, new FreeTextMasker());

        ninoMaster = new Person(UUID.randomUUID(), "Nino Beridze");
        masters.add(ninoMaster);
        masters.add(new Person(UUID.randomUUID(), "Giorgi Kapanadze"));
        annaIvanova = clients.add("Anna Ivanova");
        annaPetrova = clients.add("Anna Petrova");
        maria = clients.add("Maria Sokolova");
        ninoClient = clients.add("Nino Kvaratskhelia");
    }

    private Optional<UUID> client(String token) {
        return registry.resolve(convo, token, RefKind.CLIENT);
    }

    @Test
    void aUniqueClientBecomesOneRefThatStaysStable() {
        assertEquals("How many visits did CLIENT_1 have?", masker.mask(convo, "How many visits did Maria have?"));
        assertEquals(Optional.of(maria.id()), client("CLIENT_1"));
        assertEquals("And CLIENT_1 last month?", masker.mask(convo, "And maria last month?"));
    }

    @Test
    void anAmbiguousNameListsEveryCandidate() {
        assertEquals("Move (CLIENT_1|CLIENT_2) to Friday", masker.mask(convo, "Move Anna to Friday"));
        assertEquals(Set.of(annaIvanova.id(), annaPetrova.id()),
                Set.of(client("CLIENT_1").orElseThrow(), client("CLIENT_2").orElseThrow()));
    }

    @Test
    void nameAndSurnameOfOneClientAreOneSpan() {
        assertEquals("Move CLIENT_1 to Friday", masker.mask(convo, "Move Anna Petrova to Friday"));
        assertEquals(Optional.of(annaPetrova.id()), client("CLIENT_1"));
        assertEquals(Optional.empty(), client("CLIENT_2"), "no ref for the other Anna");
        assertEquals("CLIENT_1 again", masker.mask(convo, "PETROVA ANNA again"));
    }

    @Test
    void separatedNamesAreSeparateSpans() {
        assertEquals("(CLIENT_1|CLIENT_2), CLIENT_3 and Petra",
                masker.mask(convo, "Anna, Maria and Petra"));
    }

    @Test
    void aMasterAndAClientWithTheSameNameAreBothCandidates() {
        assertEquals("Is (MASTER_1|CLIENT_1) free tomorrow?", masker.mask(convo, "Is nino free tomorrow?"));
        assertEquals(Optional.of(ninoMaster.id()), registry.resolve(convo, "MASTER_1", RefKind.MASTER));
        assertEquals(Optional.of(ninoClient.id()), client("CLIENT_1"));
        assertEquals("Book with MASTER_1", masker.mask(convo, "Book with Nino Beridze"));
    }

    @Test
    void moreThanFiveCandidatesAreNotListed() {
        for (int i = 0; i < 6; i++) {
            clients.add("Lena " + (char) ('A' + i) + "lexeeva");
        }
        assertEquals("Call [name:many] back", masker.mask(convo, "Call Lena back"));
        assertEquals(Set.of(), Set.copyOf(registry.displays(convo).keySet()), "no refs for an unlisted crowd");
    }

    @Test
    void cyrillicAndGeorgianNamesAreMatchedCaseInsensitively() {
        Person oleg = clients.add("Олег Смирнов");
        Person giorgi = clients.add("გიორგი მელაძე");

        assertEquals("Где CLIENT_1?", masker.mask(convo, "Где олег СМИРНОВ?"));
        assertEquals("CLIENT_2 მოვა?", masker.mask(convo, "გიორგი მელაძე მოვა?"));
        assertEquals(Optional.of(oleg.id()), client("CLIENT_1"));
        assertEquals(Optional.of(giorgi.id()), client("CLIENT_2"));
    }

    @Test
    void partsOfLongerWordsAreNotNames() {
        clients.add("Andrew Theodore");
        String text = "Annabel and the others asked about Marianne";
        assertEquals(text, masker.mask(convo, text));
    }

    @Test
    void contactsAreMaskedButStopWordsAreNot() {
        assertEquals("CLIENT_1 phone is [phone], mail [email]. Who has an allergy note?",
                masker.mask(convo, "Maria phone is +995 555 12 34 56, mail maria.s@gmail.com. Who has an allergy note?"));
    }

    @Test
    void aResultAtTheCapFailsClosedWithoutRefs() {
        for (int i = 0; i < CrmService.NAME_WORD_MATCH_LIMIT; i++) {
            clients.add("Oanna " + i);
        }
        assertEquals("[name:many] 5 [name:many]", masker.mask(convo, "Call Oanna 5 back"));
        assertEquals(Map.of(), registry.displays(convo));
    }

    @Test
    void oneClientQueryPerMessageAndNoTailDegradation() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            words.append(" w").append((char) ('a' + i % 26)).append((char) ('a' + i / 26)).append("x");
        }
        String masked = masker.mask(convo, "Maria," + words + ", Anna Petrova");

        assertEquals(1, clients.queries.size());
        assertEquals("CLIENT_1," + words + ", CLIENT_2", masked);
    }

    @Test
    void refsDoNotGlueToNeighbours() {
        assertEquals("CLIENT_1 1990", masker.mask(convo, "Maria1990"));
        assertEquals("CLIENT_1 2 (CLIENT_2|CLIENT_3)", masker.mask(convo, "Maria2Anna"));
        assertEquals("CLIENT_3", masker.mask(convo, "Anna_Petrova"));
        assertEquals("Maria Sokolova 1990", new Restorer(registry).restore(convo, "CLIENT_1 1990"));
    }

    @Test
    void wordsAreNormalisedAndYoIsYeBothWays() {
        Person jose = clients.add("José Garcia");
        Person semyon = clients.add("Семён Зайцев");
        Person semen = clients.add("Семен Волков");

        assertEquals("CLIENT_1", masker.mask(convo, "Jose\u0301"));
        assertEquals(Optional.of(jose.id()), client("CLIENT_1"));
        assertEquals("(CLIENT_2|CLIENT_3)", masker.mask(convo, "Семён"));
        assertEquals("(CLIENT_2|CLIENT_3)", masker.mask(convo, "семен"));
        assertEquals("CLIENT_2", masker.mask(convo, "Семен Зайцев"));
        assertEquals("CLIENT_3", masker.mask(convo, "Семён Волков"));
        assertEquals(Set.of(semyon.id(), semen.id()),
                Set.of(client("CLIENT_2").orElseThrow(), client("CLIENT_3").orElseThrow()));
    }

    @Test
    void shortNamesMatchAsFullNamesInAnyOrderCaseAndGap() {
        Person li = clients.add("Ли Ян");

        for (String text : List.of("Ли Ян", "Ян Ли", "Ли  Ян", "Ли-Ян", "ли ян", "ЯН ЛИ")) {
            assertEquals("Где CLIENT_1?", masker.mask(convo, "Где " + text + "?"), text);
        }
        assertEquals(Optional.of(li.id()), client("CLIENT_1"));
    }

    @Test
    void aShortNameNarrowsALongOne() {
        Person liAnna = clients.add("Ли Анна");

        assertEquals("CLIENT_1", masker.mask(convo, "Ли Анна"));
        assertEquals("CLIENT_1", masker.mask(convo, "анна ли"));
        assertEquals(Optional.of(liAnna.id()), client("CLIENT_1"));
    }

    @Test
    void aShortWordAloneCountsOnlyWhenCapitalised() {
        clients.add("Ли Ян");
        Person master = new Person(UUID.randomUUID(), "Ю Ким");
        masters.add(master);

        assertEquals("Пришла ли она", masker.mask(convo, "Пришла ли она"));
        assertEquals("Спроси CLIENT_1", masker.mask(convo, "Спроси Ли"));
        assertEquals("Спроси MASTER_1", masker.mask(convo, "Спроси Ю"));
    }

    @Test
    void refsOfThisConversationStayAndForeignOnesAreNeutralised() {
        masker.mask(convo, "Maria");

        assertEquals("CLIENT_1 and [ref] and CLIENT_1", masker.mask(convo, "CLIENT_1 and CLIENT_9 and client_1"));
    }

    @Test
    void placeholderWordsAndRefsAreNotLookedUp() {
        clients.add("Phone Booth");
        masker.mask(convo, "Maria");
        clients.queries.clear();

        assertEquals("[phone] CLIENT_1", masker.mask(convo, "+995 555 12 34 56 CLIENT_1"));
        assertEquals(List.of(), clients.queries);
    }

    @Test
    void inflectedFormsOfAClientAndOfAMasterAreRecognised() {
        Person anna = clients.add("Анна Петрова");
        clients.add("Мария Новак");
        masters.add(new Person(UUID.randomUUID(), "Иван Сидоров"));
        masker = new OwnerMessageMasker(() -> masters, clients, registry, new FreeTextMasker());

        String masked = masker.mask(convo, "Перенеси Анну Петрову к Ивану, спроси Марию и Петровой тоже");

        assertEquals("Перенеси CLIENT_1 к MASTER_1, спроси CLIENT_2 и CLIENT_1 тоже", masked);
        assertEquals(Optional.of(anna.id()), registry.resolve(convo, "CLIENT_1", RefKind.CLIENT));
    }

    @Test
    void aThreeLetterNameIsFoundWhenCapitalisedOnly() {
        clients.add("Оля Петрова");
        clients.add("Мар'яна Коваль");

        assertEquals("Перенеси CLIENT_1", masker.mask(convo, "Перенеси Олю Петрову"));
        assertEquals("Спроси CLIENT_1", masker.mask(convo, "Спроси Олю"));
        assertEquals("спроси олю", masker.mask(convo, "спроси олю"));
        assertEquals("Спроси CLIENT_2", masker.mask(convo, "Спроси Мар'яну"));
    }

    @Test
    void anOrdinaryWordSharingAStemStaysAsTyped() {
        clients.add("Вера Завтра");

        assertEquals("верно, завтрак", masker.mask(convo, "верно, завтрак"));
    }

    @Test
    void aTwoLetterGeorgianNameCountsAsCapitalised() {
        clients.add("ია მელაძე");

        assertEquals("CLIENT_1 მოვიდა", masker.mask(convo, "ია მოვიდა"));
    }

    @Test
    void aTwoLetterGeorgianNameFailsClosedOnATruncatedResult() {
        for (int i = 0; i < CrmService.NAME_WORD_MATCH_LIMIT; i++) {
            clients.add("ია მელაძე");
        }

        assertEquals("[name:many]", masker.mask(convo, "ია მოვიდა"));
    }

    @Test
    void aShortWordStaysExact() {
        clients.add("Ива Новак");
        assertEquals("Иван пришёл", masker.mask(convo, "Иван пришёл"));
    }
}
