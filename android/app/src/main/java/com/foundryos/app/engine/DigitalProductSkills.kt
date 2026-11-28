package com.foundryos.app.engine

/**
 * Market-Analyzed Sellable Digital Products Skill Catalog (Gumroad, Etsy, LemonSqueezy,
 * Notion Marketplace, Creative Market, Whop, App & Template Stores).
 * Guides the CEO and specialist bots to pick the highest-converting multi-format deliverables
 * instead of defaulting to a single plain PDF.
 */
object DigitalProductSkills {

    val ALL_PRODUCT_TOOLS = listOf(
        "create_pdf",
        "create_xlsx",
        "create_pptx",
        "create_docx",
        "create_png_graphic",
        "create_svg_graphic",
        "create_web_app",
        "create_notion_pack",
        "create_csv_dataset",
        "create_zip",
    )

    fun marketCapabilitiesPrompt(): String = """
        SELLABLE DIGITAL PRODUCT CAPABILITIES (ALL 100% ON-DEVICE — PICK THE BEST FORMATS FOR THE MARKET, NEVER LIMIT TO PDF ONLY):
        1. `create_xlsx` — Real multi-tab Excel & Google Sheets workbooks (.xlsx) with live formulas (=SUM, =AVERAGE, =B2-C2) & styled headers. Top sellers: budgets, finance trackers, KPI dashboards, content calendars, CRMs, habit trackers.
        2. `create_pptx` — Real 16:9 PowerPoint / Google Slides / Canva decks (.pptx). Top sellers: course slides, pitch decks, client proposals, brand playbooks, LinkedIn/IG carousel decks.
        3. `create_docx` — Editable Microsoft Word / Google Docs templates (.docx). Top sellers: legal/freelance contracts, SOPs, resume kits, onboarding packets, editable guides.
        4. `create_pdf` — Illustrated multi-chapter editorial books, workbooks & printable planners (.pdf) with full cover, TOC, checklists, tables & vector charts.
        5. `create_notion_pack` — Ready-to-import Notion workspace bundles (.zip of Markdown pages + CSV databases + import guide). Top sellers: Life OS, Second Brain, Creator/Agency OS.
        6. `create_web_app` — Self-contained interactive HTML5/CSS/JS micro-apps, calculators, dashboards, quiz/course apps & high-converting sales landing pages (.html).
        7. `create_png_graphic` — High-resolution PNG product covers, Etsy/Gumroad listing thumbnails, mockup hero cards & social graphics (.png).
        8. `create_svg_graphic` — Scalable vector infographics, charts, icons, printable wall art & brand diagrams (.svg).
        9. `create_csv_dataset` — UTF-8 BOM CSV datasets (.csv) for AI prompt packs, SEO keyword lists, swipe files, lead/outreach matrices & automation templates.
        10. `create_zip` — Customer-ready commercial delivery bundle (.zip) automatically bundling all output files with a customer START_HERE guide.

        MARKET PACKAGING STANDARD:
        - High-value digital products ($19–$99 bundles) combine complementary formats (e.g. .xlsx interactive tracker + .docx editable template OR .pptx course deck OR Notion pack + .pdf illustrated handbook + .png cover graphic + .html interactive app/sales page + customer .zip bundle).
        - Match the exact formats buyers expect for that niche instead of forcing everything into one PDF.
    """.trimIndent()
}
