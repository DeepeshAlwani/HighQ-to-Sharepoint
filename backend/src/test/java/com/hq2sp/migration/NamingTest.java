package com.hq2sp.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NamingTest {

    @Test
    void keepsHighQNumbering() {
        assertEquals("1 Structure", Naming.localName("1", "Structure", null));
        assertEquals("4.1 Test", Naming.localName("4.1", "Test", ""));
        assertEquals("19 HR_PerformanceImprovementPolicy.docx", Naming.localName("19", "HR_PerformanceImprovementPolicy", "docx"));
        assertEquals("Plain.pdf", Naming.localName(null, "Plain", "pdf"));
    }

    @Test
    void makesNamesSafeForWindows() {
        assertEquals("3 a_b_c_d.txt", Naming.localName("3", "a:b/c?d", "txt"));
        assertEquals("_CON", Naming.localName(null, "CON", null));
        assertEquals("7 Trailing dot", Naming.localName("7", "Trailing dot. ", null));
        assertEquals("_", Naming.localName(null, "   ", null));
        // brackets are legal and must survive untouched
        assertEquals("5.2 Form[N1]{x}.docx", Naming.localName("5.2", "Form[N1]{x}", "docx"));
    }

    @Test
    void shortNameKeepsNumberIdAndExtension() {
        assertEquals("3.1 ~4857078.msg", Naming.shortName("3.1", 4857078, "msg"));
    }

    @Test
    void zipPathsKeepStructureAndBlockZipSlip() {
        assertEquals("Demo site/1 Structure/a.docx", Naming.safeZipPath("Demo site/1 Structure/a.docx"));
        assertEquals("folder/sub/b_.pdf", Naming.safeZipPath("folder\\sub\\b?.pdf"));
        assertEquals("x/y.txt", Naming.safeZipPath("./x/./y.txt"));
        assertNull(Naming.safeZipPath("../evil.txt"));
        assertNull(Naming.safeZipPath("a/../../evil.txt"));
        assertNull(Naming.safeZipPath("/etc/passwd"));
        assertNull(Naming.safeZipPath("C:/Windows/x.dll"));
        assertNull(Naming.safeZipPath(""));
    }

    @Test
    void parsesHighQDatesAsUtc() {
        assertEquals("2026-11-06T09:32:00Z", Naming.highQDateToIso("06 Nov 2026 09:32"));
        assertEquals("2021-09-15T09:41:00Z", Naming.highQDateToIso("15 Sep 2021 09:41"));
        assertNull(Naming.highQDateToIso("not a date"));
        assertNull(Naming.highQDateToIso(null));
    }
}
