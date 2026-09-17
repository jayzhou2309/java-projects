package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The check types over the answer-visibility diagnostic's committed output (plan {@code 2026-09-17-chunk-size.md}, Milestone 1; RAG.md,
 * Claims, Check types): {@code diagnosticLine} (one printed field, observed), {@code diagnosticCount} (lines naming a question, derived),
 * {@code sizeTable} (the size rule's counts at one size, derived), and {@code sizeChoice} (the frozen rule, derived), over a small output
 * in the printed format written to a temporary evidence root: a console line without the prefix is ignored, sentences are rendered from the
 * values found with the line number, a claim of another value fails naming the line, selectors matching two lines or none are refused, the
 * TOTALS line is not counted, a split phrase excludes its size, the larger size wins a tie, and no size is chosen when none beats the stored one.
 */
class DiagnosticChecksTests {
    private static final String SCORING = "max-window/overlap=64/maxWindows=4";

    /**
     * Three located phrases over two questions (q1 in chunk 754; q2 in chunks 802 and 803), the four sizes of the real run. The ranks of q2's
     * chunk 802 at 4,000 and 2,000 characters are parameters; at 500 characters no piece holds that phrase. Line 1 is a console line without
     * the prefix, so the setup line is line 2 and the first chunkSize line is line 13.
     */
    private static String output(int q2At4000, int q2At2000) {
        return """
                2026-09-17T10:25:43.554+08:00  INFO 86908 --- [StockRecommendationEngine] [           main] p.s.rag.retrieval.CrossEncoderReranker   : Cross-encoder scoring completed: candidates=20, windows=47, topK=20, elapsedMs=1255
                ANSWER_VISIBILITY setup set=v2 resource=evaluation/retrieval-set-v2.json questions=2 questionsMeasured=all acceptedPhrasesLocated=3 acceptedPhrasesNotInAnyStoredChunk=0[] model=5d3e70fd0c9f maxLength=512 batchSize=20 configuredScoring=%1$s
                ANSWER_VISIBILITY visibility question=q1 kind=FIGURE ticker=NVDA chunk=754 section=item7 questionTokens=12 chunkTokens=721 windowLength=486 phraseTokens=[547, 567) headMembership=NOT scoring=%1$s windowStarts=[0, 422] windowsHoldingPhrase=[2] phrase="net revenue was $1,000 million, up 20%%"
                ANSWER_VISIBILITY visibility question=q2 kind=NARRATIVE ticker=NVDA chunk=802 section=item1a questionTokens=9 chunkTokens=300 windowLength=486 phraseTokens=[10, 20) headMembership=WHOLLY scoring=%1$s windowStarts=[0] windowsHoldingPhrase=[1] phrase="a risk"
                ANSWER_VISIBILITY visibility question=q2 kind=NARRATIVE ticker=NVDA chunk=803 section=item1a questionTokens=9 chunkTokens=310 windowLength=486 phraseTokens=[30, 40) headMembership=WHOLLY scoring=%1$s windowStarts=[0] windowsHoldingPhrase=[1] phrase="another risk"
                ANSWER_VISIBILITY visibility TOTALS phrases=3 headWholly=2 headPartly=0 headNot=1 scoring=%1$s someRowHoldsPhrase=3 noRowHoldsPhrase=0[]
                ANSWER_VISIBILITY truncation question=q1 chunk=754 chunkTokens=721 windowLength=486 phraseTokenStart=547 headMembership=NOT windowsHoldingPhrase=[2] headScore=-3.5 windowedScore(%1$s)=-0.65 recutScore(from 200 chars before the phrase)=5.16 phraseAloneScore=2.0 windowedMinusHead=2.85 recutMinusHead=8.66
                ANSWER_VISIBILITY truncation question=q2 chunk=802 chunkTokens=300 windowLength=486 phraseTokenStart=10 headMembership=WHOLLY windowsHoldingPhrase=[1] headScore=1.0 windowedScore(%1$s)=1.0 recutScore(from 200 chars before the phrase)=1.5 phraseAloneScore=0.5 windowedMinusHead=0.0 recutMinusHead=0.5
                ANSWER_VISIBILITY truncation question=q2 chunk=803 chunkTokens=310 windowLength=486 phraseTokenStart=30 headMembership=WHOLLY windowsHoldingPhrase=[1] headScore=2.0 windowedScore(%1$s)=2.0 recutScore(from 200 chars before the phrase)=2.5 phraseAloneScore=1.5 windowedMinusHead=0.0 recutMinusHead=0.5
                ANSWER_VISIBILITY rank question=q1 kind=FIGURE section=item7 poolChunks=35 chunksHoldingPhrase=[754] headRanks=[7] windowedRanks=[2] bestHeadRank=7 bestWindowedRank(%1$s)=2 headTop5=[755, 760, 731, 733, 740] windowedTop5=[755, 754, 760, 731, 733]
                ANSWER_VISIBILITY rank question=q2 kind=NARRATIVE section=item1a poolChunks=60 chunksHoldingPhrase=[802] headRanks=[1] windowedRanks=[1] bestHeadRank=1 bestWindowedRank(%1$s)=1 headTop5=[802, 803, 810, 811, 812] windowedTop5=[802, 803, 810, 811, 812]
                ANSWER_VISIBILITY rank question=q2 kind=NARRATIVE section=item1a poolChunks=60 chunksHoldingPhrase=[803] headRanks=[2] windowedRanks=[2] bestHeadRank=2 bestWindowedRank(%1$s)=2 headTop5=[802, 803, 810, 811, 812] windowedTop5=[802, 803, 810, 811, 812]
                ANSWER_VISIBILITY chunkSize question=q1 kind=FIGURE storedChunk=754 sizeChars=4000 overlapChars=500 pieces=35 piecesHoldingPhrase=1 bestRank=2 isStoredSize=true holders=[piece20{chars=3900 tokens=721 windowLength=486 phraseTokenStart=547 headMembership=NOT windowsHoldingPhrase=[2] phraseSeen=true score=-0.65 rank=2}]
                ANSWER_VISIBILITY chunkSize question=q1 kind=FIGURE storedChunk=754 sizeChars=2000 overlapChars=250 pieces=70 piecesHoldingPhrase=1 bestRank=4 isStoredSize=false holders=[piece41{chars=1994 tokens=472 windowLength=486 phraseTokenStart=214 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=-2.68 rank=4}]
                ANSWER_VISIBILITY chunkSize question=q1 kind=FIGURE storedChunk=754 sizeChars=1000 overlapChars=125 pieces=79 piecesHoldingPhrase=1 bestRank=1 isStoredSize=false holders=[piece70{chars=812 tokens=157 windowLength=486 phraseTokenStart=54 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=4.3373547 rank=1}]
                ANSWER_VISIBILITY chunkSize question=q1 kind=FIGURE storedChunk=754 sizeChars=500 overlapChars=62 pieces=160 piecesHoldingPhrase=2 bestRank=1 isStoredSize=false holders=[piece140{chars=498 tokens=90 windowLength=486 phraseTokenStart=60 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=4.87 rank=1}, piece141{chars=470 tokens=85 windowLength=486 phraseTokenStart=2 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=3.1 rank=5}]
                ANSWER_VISIBILITY chunkSize question=q2 kind=NARRATIVE storedChunk=802 sizeChars=4000 overlapChars=500 pieces=60 piecesHoldingPhrase=1 bestRank=%2$d isStoredSize=true holders=[piece30{chars=3800 tokens=2100 windowLength=486 phraseTokenStart=1900 headMembership=NOT windowsHoldingPhrase=[] phraseSeen=false score=1.0 rank=%2$d}]
                ANSWER_VISIBILITY chunkSize question=q2 kind=NARRATIVE storedChunk=802 sizeChars=2000 overlapChars=250 pieces=120 piecesHoldingPhrase=1 bestRank=%3$d isStoredSize=false holders=[piece61{chars=1900 tokens=400 windowLength=486 phraseTokenStart=10 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=0.9 rank=%3$d}]
                ANSWER_VISIBILITY chunkSize question=q2 kind=NARRATIVE storedChunk=802 sizeChars=1000 overlapChars=125 pieces=240 piecesHoldingPhrase=1 bestRank=1 isStoredSize=false holders=[piece121{chars=990 tokens=200 windowLength=486 phraseTokenStart=10 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=2.2 rank=1}]
                ANSWER_VISIBILITY chunkSize question=q2 storedChunk=802 sizeChars=500 pieces=480 noPieceHoldsPhrase=true
                ANSWER_VISIBILITY chunkSize question=q2 kind=NARRATIVE storedChunk=803 sizeChars=4000 overlapChars=500 pieces=60 piecesHoldingPhrase=1 bestRank=1 isStoredSize=true holders=[piece31{chars=3810 tokens=2050 windowLength=486 phraseTokenStart=30 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=2.0 rank=1}]
                ANSWER_VISIBILITY chunkSize question=q2 kind=NARRATIVE storedChunk=803 sizeChars=2000 overlapChars=250 pieces=120 piecesHoldingPhrase=1 bestRank=1 isStoredSize=false holders=[piece62{chars=1950 tokens=410 windowLength=486 phraseTokenStart=30 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=2.1 rank=1}]
                ANSWER_VISIBILITY chunkSize question=q2 kind=NARRATIVE storedChunk=803 sizeChars=1000 overlapChars=125 pieces=240 piecesHoldingPhrase=1 bestRank=2 isStoredSize=false holders=[piece122{chars=980 tokens=210 windowLength=486 phraseTokenStart=30 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=1.9 rank=2}]
                ANSWER_VISIBILITY chunkSize question=q2 kind=NARRATIVE storedChunk=803 sizeChars=500 overlapChars=62 pieces=480 piecesHoldingPhrase=1 bestRank=1 isStoredSize=false holders=[piece243{chars=495 tokens=95 windowLength=486 phraseTokenStart=30 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=3.0 rank=1}]
                """.formatted(SCORING, q2At4000, q2At2000);
    }

    private static Path write(Path temp, String output, String claims) throws IOException {
        Files.createDirectories(temp.resolve("evidence"));
        Files.writeString(temp.resolve("evidence/output.log"), output);
        Path file = temp.resolve("claims.json");
        Files.writeString(file, claims);
        return file;
    }

    private static final String OUTPUT = "evidence/output.log";

    @Test
    void aPrintedFieldIsComparedAsPrintedAndRenderedWithItsLineNumber(@TempDir Path temp) throws IOException {
        Path claims = write(temp, output(1, 3), """
                {"claims": [
                  {"id": "C-001", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "setup", "field": "acceptedPhrasesLocated", "expected": 3}},
                  {"id": "C-002", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "chunkSize", "question": "q1", "chunk": 754, "sizeChars": 1000, "field": "bestRank", "expected": 1}},
                  {"id": "C-003", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "chunkSize", "question": "q1", "chunk": 754, "sizeChars": 1000, "field": "holders", "expected": "[piece70{chars=812 tokens=157 windowLength=486 phraseTokenStart=54 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=4.3373547 rank=1}]"}},
                  {"id": "C-004", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "truncation", "question": "q1", "chunk": 754, "field": "windowedScore(%2$s)", "expected": -0.650}},
                  {"id": "C-005", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "rank", "question": "q2", "chunk": 803, "field": "bestWindowedRank(%2$s)", "expected": 2}},
                  {"id": "C-006", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "visibility", "question": "q1", "field": "phrase", "expected": "\\"net revenue was $1,000 million, up 20%%\\""}},
                  {"id": "C-007", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "truncation", "question": "q1", "field": "recutScore(from 200 chars before the phrase)", "expected": 5.16}},
                  {"id": "C-008", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "visibility", "line": 6, "field": "headNot", "expected": 1}}
                ]}
                """.formatted(OUTPUT, SCORING));
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("Line 2 of the diagnostic output evidence/output.log, the setup line, records acceptedPhrasesLocated 3.");
        assertThat(result.sentences().get(2)).isEqualTo("Line 15 of the diagnostic output evidence/output.log, the chunkSize line of q1, chunk 754, at 1000 characters, records bestRank 1.");
        assertThat(result.sentences().get(3)).isEqualTo("Line 15 of the diagnostic output evidence/output.log, the chunkSize line of q1, chunk 754, at 1000 characters, records holders "
                + "[piece70{chars=812 tokens=157 windowLength=486 phraseTokenStart=54 headMembership=WHOLLY windowsHoldingPhrase=[1] phraseSeen=true score=4.3373547 rank=1}].");
        assertThat(result.sentences().get(4)).isEqualTo("Line 7 of the diagnostic output evidence/output.log, the truncation line of q1, chunk 754, records windowedScore(" + SCORING + ") -0.65.");
        assertThat(result.sentences().get(5)).isEqualTo("Line 12 of the diagnostic output evidence/output.log, the rank line of q2, chunk 803, records bestWindowedRank(" + SCORING + ") 2.");
        assertThat(result.sentences().get(6)).isEqualTo("Line 3 of the diagnostic output evidence/output.log, the visibility line of q1, records phrase \"net revenue was $1,000 million, up 20%\".");
        assertThat(result.sentences().get(7)).isEqualTo("Line 7 of the diagnostic output evidence/output.log, the truncation line of q1, records recutScore(from 200 chars before the phrase) 5.16.");
        assertThat(result.sentences().get(8)).isEqualTo("Line 6 of the diagnostic output evidence/output.log, the visibility TOTALS line, records headNot 1.");
    }

    @Test
    void anotherValueFailsNamingTheLineAndSelectorsMatchingTwoLinesOrNoneOrAMissingFieldAreRefused(@TempDir Path temp) throws IOException {
        Path claims = write(temp, output(1, 3), """
                {"claims": [
                  {"id": "C-001", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "chunkSize", "question": "q1", "chunk": 754, "sizeChars": 1000, "field": "bestRank", "expected": 2}},
                  {"id": "C-002", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "rank", "question": "q2", "field": "bestHeadRank", "expected": 1}},
                  {"id": "C-003", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "rank", "question": "q3", "field": "bestHeadRank", "expected": 1}},
                  {"id": "C-004", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "setup", "field": "chosenSize", "expected": 1000}},
                  {"id": "C-005", "basis": "derived", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "setup", "field": "questions", "expected": 2}},
                  {"id": "C-006", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "setup", "field": "questions", "expected": "2"}},
                  {"id": "C-007", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "setup", "field": "model", "expected": "5d3e70fd0c9f", "snapshot": "x"}},
                  {"id": "C-008", "basis": "observed", "check": {"type": "diagnosticLine", "output": "%1$s", "experiment": "rank", "line": 6, "field": "phrases", "expected": 3}}
                ]}
                """.formatted(OUTPUT));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-001 [diagnosticLine] bestRank of the chunkSize line of q1, chunk 754, at 1000 characters in evidence/output.log: bestRank expected 2, found 1 (line 15)",
                name + " C-002 [diagnosticLine] bestHeadRank of the rank line of q2 in evidence/output.log: evidence/output.log has 2 rank lines matching question q2 (lines 11, 12), expected exactly one",
                name + " C-003 [diagnosticLine] bestHeadRank of the rank line of q3 in evidence/output.log: evidence/output.log has no rank line matching question q3, expected exactly one",
                name + " C-004 [diagnosticLine] chosenSize of the setup line in evidence/output.log: line 2 has no field chosenSize (its fields: set, resource, questions, questionsMeasured, "
                        + "acceptedPhrasesLocated, acceptedPhrasesNotInAnyStoredChunk, model, maxLength, batchSize, configuredScoring)",
                name + " C-005 [diagnosticLine] questions of the setup line in evidence/output.log: basis expected derived, found observed",
                name + " C-007 [diagnosticLine]: unknown check key \"snapshot\"",
                name + " C-008 [diagnosticLine] phrases of the rank line (line 6) in evidence/output.log: evidence/output.log has no rank line matching line 6, expected exactly one");
    }

    @Test
    void linesNamingAQuestionAreCountedPerExperimentAndSizeWithoutTheTotalsLine(@TempDir Path temp) throws IOException {
        Path claims = write(temp, output(1, 3), """
                {"labels": {"%1$s": "the diagnostic run"},
                 "claims": [
                  {"id": "C-001", "basis": "derived", "check": {"type": "diagnosticCount", "output": "%1$s", "experiment": "visibility", "expected": 3}},
                  {"id": "C-002", "basis": "derived", "check": {"type": "diagnosticCount", "output": "%1$s", "experiment": "chunkSize", "sizeChars": 500, "expected": 3}},
                  {"id": "C-003", "basis": "derived", "check": {"type": "diagnosticCount", "output": "%1$s", "experiment": "overlap", "expected": 0}},
                  {"id": "C-004", "basis": "derived", "check": {"type": "diagnosticCount", "output": "%1$s", "experiment": "rank", "expected": 2}}
                ]}
                """.formatted(OUTPUT));
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        String name = ClaimsCheck.display(claims);
        assertThat(result.problems()).containsExactly(name + " C-004 [diagnosticCount] rank lines in evidence/output.log: count expected 2, found 3");
        assertThat(result.sentences().get(1)).isEqualTo("The diagnostic run (the diagnostic output evidence/output.log) holds 3 visibility lines naming a question.");
        assertThat(result.sentences().get(2)).isEqualTo("The diagnostic run (the diagnostic output evidence/output.log) holds 3 chunkSize lines naming a question at 500 characters.");
        assertThat(result.sentences().get(3)).isEqualTo("The diagnostic run (the diagnostic output evidence/output.log) holds 0 overlap lines naming a question.");
    }

    private static String table(int size, int phrases, int firstRanked, int split, int unseen) {
        return "{\"id\": \"C-%d\", \"basis\": \"derived\", \"check\": {\"type\": \"sizeTable\", \"output\": \"%s\", \"sizeChars\": %d, \"expected\": {\"phrases\": %d, \"firstRanked\": %d, \"split\": %d, \"unseen\": %d}}}"
                .formatted(size, OUTPUT, size, phrases, firstRanked, split, unseen);
    }

    @Test
    void theSizeTableCountsFirstRankedSplitAndUnseenPhrasesPerSizeAndAFailureNamesThePhrases(@TempDir Path temp) throws IOException {
        Path claims = write(temp, output(1, 3), "{\"claims\": [" + table(4000, 3, 2, 0, 1) + ", " + table(2000, 3, 1, 0, 0) + ", " + table(1000, 3, 2, 0, 0) + ", " + table(500, 3, 2, 1, 0) + "]}");
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("At 4000 characters (overlap 500), the diagnostic output evidence/output.log records 3 phrases: 2 with the holding piece ranked first in its section pool, "
                + "0 split by a piece boundary, and 1 whose holding pieces are seen by no scored row.");
        assertThat(result.sentences().get(4)).isEqualTo("At 500 characters (overlap 62), the diagnostic output evidence/output.log records 3 phrases: 2 with the holding piece ranked first in its section pool, "
                + "1 split by a piece boundary, and 0 whose holding pieces are seen by no scored row.");

        Files.writeString(claims, "{\"claims\": [" + table(4000, 3, 3, 0, 0) + ", " + table(500, 3, 3, 0, 0) + ", " + table(250, 3, 3, 0, 0) + "]}");
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-4000 [sizeTable] 4000 characters in evidence/output.log: firstRanked expected 3, found 2 (not first: q1 (stored chunk 754, line 13) bestRank 2); "
                        + "unseen expected 0, found 1 (q2 (stored chunk 802, line 17))",
                name + " C-500 [sizeTable] 500 characters in evidence/output.log: firstRanked expected 3, found 2; split expected 0, found 1 (q2 (stored chunk 802, line 20))",
                name + " C-250 [sizeTable] 250 characters in evidence/output.log: evidence/output.log has no chunkSize line at 250 characters");
    }

    private static String choice(String expected, String candidates) {
        return "{\"claims\": [{\"id\": \"C-900\", \"basis\": \"derived\", \"check\": {\"type\": \"sizeChoice\", \"output\": \"%s\", \"stored\": 4000, \"candidates\": %s, \"expected\": %s}}]}"
                .formatted(OUTPUT, candidates, expected);
    }

    @Test
    void theFrozenRuleChoosesTheMostFirstRankedSizeWithoutASplitPhraseTheLargerOnATieAndNoneWhenTheStoredSizeIsNotBeaten(@TempDir Path temp) throws IOException {
        // Stored 4000: 2 first-ranked (802 at rank 1, 803); 2000: 1; 1000: 2; 500: excluded (802 split). No candidate beats 2.
        Path claims = write(temp, output(1, 3), choice("\"none\"", "[2000, 1000, 500]"));
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("Under the frozen size rule over the diagnostic output evidence/output.log (first-ranked phrases: 4000 characters 2, 2000 characters 1, "
                + "1000 characters 2, 500 characters 2; split phrases: 4000 characters 0, 2000 characters 0, 1000 characters 0, 500 characters 1; a size with a split phrase is excluded), "
                + "no size is chosen: no remaining size has more first-ranked phrases than the stored 4000 characters.");

        // Stored 4000: 1 first-ranked (802 at rank 2); 1000 has 2 and is chosen.
        Path chosen = write(temp, output(2, 3), choice("1000", "[2000, 1000, 500]"));
        result = ClaimsCheck.evaluate(chosen, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("Under the frozen size rule over the diagnostic output evidence/output.log (first-ranked phrases: 4000 characters 1, 2000 characters 1, "
                + "1000 characters 2, 500 characters 2; split phrases: 4000 characters 0, 2000 characters 0, 1000 characters 0, 500 characters 1; a size with a split phrase is excluded), "
                + "the chosen size is 1000 characters.");

        // A tie between 2000 and 1000 at 2 first-ranked goes to the larger size.
        Path tie = write(temp, output(2, 1), choice("2000", "[2000, 1000, 500]"));
        result = ClaimsCheck.evaluate(tie, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).startsWith("Under the frozen size rule over the diagnostic output evidence/output.log (first-ranked phrases: 4000 characters 1, 2000 characters 2, 1000 characters 2, 500 characters 2;")
                .endsWith("the chosen size is 2000 characters.");

        // Only the excluded size as a candidate: nothing remains.
        Path excluded = write(temp, output(2, 1), choice("\"none\"", "[500]"));
        result = ClaimsCheck.evaluate(excluded, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("Under the frozen size rule over the diagnostic output evidence/output.log (first-ranked phrases: 4000 characters 1, 500 characters 2; "
                + "split phrases: 4000 characters 0, 500 characters 1; a size with a split phrase is excluded), no size is chosen: no candidate size remains.");
    }

    @Test
    void aWrongChoiceFailsWithTheCountsAndMalformedChoicesAreRefused(@TempDir Path temp) throws IOException {
        Path claims = write(temp, output(2, 3), """
                {"claims": [
                  {"id": "C-901", "basis": "derived", "check": {"type": "sizeChoice", "output": "%1$s", "stored": 4000, "candidates": [2000, 1000, 500], "expected": "none"}},
                  {"id": "C-902", "basis": "observed", "check": {"type": "sizeChoice", "output": "%1$s", "stored": 4000, "candidates": [2000, 1000, 500], "expected": 1000}},
                  {"id": "C-903", "basis": "derived", "check": {"type": "sizeChoice", "output": "%1$s", "stored": 4000, "candidates": [2000, 1000, 500], "expected": 3000}},
                  {"id": "C-904", "basis": "derived", "check": {"type": "sizeChoice", "output": "%1$s", "stored": 4000, "candidates": [2000, 4000], "expected": 2000}},
                  {"id": "C-905", "basis": "derived", "check": {"type": "sizeChoice", "output": "%1$s", "stored": 4000, "candidates": [], "expected": "none"}}
                ]}
                """.formatted(OUTPUT));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-901 [sizeChoice] stored 4000, candidates [2000, 1000, 500] in evidence/output.log: chosen size expected none, found 1000 (first-ranked: 4000 1, 2000 1, 1000 2, 500 2; split: 4000 0, 2000 0, 1000 0, 500 1)",
                name + " C-902 [sizeChoice] stored 4000, candidates [2000, 1000, 500] in evidence/output.log: basis expected observed, found derived",
                name + " C-903 [sizeChoice]: check.expected must be one of the candidate sizes or \"none\", found 3000",
                name + " C-904 [sizeChoice]: check.candidates must be distinct sizes other than the stored 4000, found [2000,4000]",
                name + " C-905 [sizeChoice]: check.candidates must list at least one size, found []");
    }
}
