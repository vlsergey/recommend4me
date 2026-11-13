package io.github.vlsergey.recommend4me.source.aff

/**
 * THE SITE'S OWN WORDS OF ITS CODES: its content tags are short codes ("MF", "Ds", "HC"), told in full by the titles of
 * its advanced search's filters — a tag's name is its code told so; the archives its subdomains are, as its menu names
 * them.
 */
internal object AffCodes {

    /** The codes that tell the status of a work, not its content: kept as its status alone. */
    const val COMPLETE = "complete"
    const val WIP = "wip"

    /** A story of one chapter: told finished unless the site's tags say it is a work in progress. */
    const val ONESHOT = "oneshot"

    /** The codes that tell whom the relationships are between, as the site's categories do too. */
    const val HET = "mf"
    const val SLASH = "mm"
    const val FEMSLASH = "ff"
    const val MANY = "3plus"

    /** The content tags told in full, by their codes in lower case: the titles of the advanced search's filters. */
    val NAMES =
        mapOf(
            "3plus" to "3Plus - Threesomes/Moresomes, any combination",
            "abdl" to "AB/DL - Adult Baby Diaper Lover",
            "abortion" to "Abortion - medical termination of a pregnancy",
            "abuse" to "Abuse - Abuse of any character, such as child abuse",
            "affo" to "AFFO - Story is found ONLY on adult-fanfiction.org",
            "ageplay" to "Ageplay - Age based play",
            "ai-as" to "AI assistance in story development (Combination of AI-SGT & AI-BS)",
            "ai-bs" to "AI was used for story brainstorming.",
            "ai-sgt" to "AI was used for spelling, grammar, & thesaurus.",
            "anal" to "Anal - Anal sex",
            "angst" to "Angst - Torment, anguish",
            "anthro" to "Anthro - Anthropomorphic/Furries/Furry/Plushy/Plushophiles",
            "bdsm" to "BDSM - Bondage & Discipline/Domination & Submission/Sadism & Masochism",
            "beast" to "Beast - Bestiality",
            "bi" to "Bi - Bisexuality",
            "bigotry" to "Bigotry - Bigotry",
            "bmod" to "B-Mod - Body modification - piercing, tattooing, scarification, etc.",
            "bond" to "Bond - Bondage",
            "bp" to "BP - Blood Play",
            "cbt" to "CBT - Cock/Ball Torture",
            "challengefic" to "ChallengeFic - Story written in response to a challenge",
            "contro" to "Contro - Controversial content even for adults.",
            "cr" to "CR - corruption",
            "cuck" to "Cuckold - A man whose wife is unfaithful",
            "cuckquean" to "Cuckquean - A woman whose husband is unfaithful",
            "dom" to "Dom - Male or Female Domination",
            "dp" to "DP - Double Penetration",
            "ds" to "D/s - Dominance/submission",
            "exhib" to "Exhib - Exhibitionism",
            "fd" to "F/D - Futanari/Dickgirls",
            "fet" to "Fet - Fetish",
            "ff" to "F/F - Femmeslash",
            "fingering" to "Fingering - manual manipulation of the clitoris, vagina, vulva or anus",
            "fist" to "Fist - Fisting",
            "gb" to "GB - Gender Bender (a canon character whose sex is changed for the story)",
            "hc" to "H/C - Hurt/Comfort",
            "herm" to "Herm - Hermaphrodite",
            "hj" to "HJ - Hand Job (manual stimulation of one partner's penis)",
            "hum" to "Hum - Humanoid (not anthro or xeno)",
            "humil" to "Humil - Humiliation",
            "inc" to "Inc - Incest",
            "mbp" to "MBP - menstrual blood play",
            "mc" to "MC - Mind Control",
            "mcd" to "MCD - Main Character Death",
            "mf" to "M/F - Het",
            "micd" to "MiCD - Minor Character Death",
            "mm" to "M/M - Slash",
            "mpreg" to "MPreg - Male Pregnancy",
            "ms" to "M/s - Master/slave relationship",
            "nec" to "Nec - Necrophilia",
            "non-fic" to "Non-fic - Non Fiction",
            "nosex" to "NoSex - No Sexual Content",
            "oc" to "OC - Original Character",
            "oneshot" to "Oneshot - Oneshot story",
            "oral" to "Oral - Oral sex",
            "other" to "Other - Other (any warning not covered in this list)",
            "parody" to "Parody - Parody (Note ALL parodies must be approved by original author)",
            "peg" to "Peg - A woman giving a man anal sex using a strap-on dildo",
            "preg" to "Preg - Pregnancy",
            "pwp" to "PWP - Porn without Plot/Point",
            "racist" to "Racist - Racism",
            "rape" to "Rape - where one of the characters is sexually assaulted",
            "rim" to "Rim - Rimming (To perform anilingus on)",
            "sandm" to "S&M - Sadism/Masochism",
            "scat" to "Scat - Poop Play",
            "sh" to "SH - Sexual Harassment",
            "si" to "SI - Self-Insertion (Mary Sue, etc.)",
            "slave" to "Slave - Slavery",
            "solo" to "Solo - Masturbation",
            "spank" to "Spank - Spanking",
            "tent" to "Tent - Tentacles",
            "tf" to "TF - Transformation (biological, physical, etc)",
            "tort" to "Tort - Torture",
            "toys" to "Toys - Sexual toys (vibrators, dildos, etc)",
            "trans" to "Trans - Transgender, Transsexual or Transvestite (cross-dresser)",
            "ust" to "UST - Unresolved Sexual Tension",
            "violence" to "Violence - Story contains violence/blood/gore/etc.",
            "voy" to "Voy - Voyeurism",
            "waff" to "WAFF - Warm and Fuzzy Feeling - Anime Specific Code",
            "wd" to "WD - Wet Dream or ejaculation while asleep",
            "ws" to "WS - Water-sports, urine play",
            "xeno" to "Xeno - Xenophilia (Sex with Aliens)",
        )

    /** The archive of the original writing: its works are no fan fiction. */
    const val ORIGINAL = "original"

    /** The archives, by their subdomains, as the site's menu names them. */
    val ARCHIVES =
        mapOf(
            "anime" to "Anime General",
            "anime2" to "Beyblade, DBZ, FMA, Gundam",
            "bleach" to "Bleach",
            "books" to "Books General",
            "buffy" to "Buffy/Angel",
            "cartoon" to "Cartoons",
            "celeb" to "Celebrities",
            "comics" to "Comics",
            "ff" to "Final Fantasy",
            "games" to "Games",
            "hp" to "Harry Potter",
            "inu" to "InuYasha",
            "lotr" to "Lord of the Rings",
            "manga" to "Manga",
            "movies" to "Movies",
            "naruto" to "Naruto",
            "ne" to "Non English",
            ORIGINAL to "Original Writing",
            "tv" to "Television",
            "xmen" to "Marvel Verse",
            "ygo" to "Yu-Gi-Oh!",
            "yuyu" to "YuYu Hakusho",
        )
}
