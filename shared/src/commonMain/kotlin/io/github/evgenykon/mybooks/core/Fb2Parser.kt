package io.github.evgenykon.mybooks.core

/**
 * FB2 (FictionBook 2.0) parser.
 *
 * Produces a [Book] with a flat block list, a table of contents and metadata.
 * The parser is deliberately tolerant: unknown elements are skipped, inline
 * formatting is reduced to its text, and a book without metadata still parses.
 */
object Fb2Parser {

    /** Parses raw FB2 bytes, honouring the encoding declared in the XML header. */
    fun parseBytes(bytes: ByteArray): Book {
        val encoding = Charsets.detectEncoding(bytes)
        val text = Charsets.decode(bytes, encoding)
        return parseText(text)
    }

    fun parseText(xml: String): Book {
        val root = Xml.parse(xml)
        val bookNode = root.elements().firstOrNull { it.name == "FictionBook" }
            ?: root.elements().firstOrNull { it.name == "fictionbook" }
            ?: root
        val description = bookNode.firstChild("description")
        val titleInfo = description?.firstChild("title-info")

        val title = titleInfo?.firstChild("book-title")?.text()?.trim().orEmpty()
            .ifEmpty { "Без названия" }
        val authors = parseAuthors(titleInfo)
        val annotation = titleInfo?.firstChild("annotation")?.let { node -> nodeText(node) }?.trim()
            ?.takeIf { it.isNotEmpty() }

        val covers = mutableMapOf<String, String>()
        val builder = BlockBuilder()

        // Cover image is referenced by id; remember binaries by id later.
        val coverId = titleInfo?.firstChild("coverpage")
            ?.firstChild("image")?.attr("href")?.removePrefix("#")

        val bodies = bookNode.childrenNamed("body")
        val mainBody = bodies.firstOrNull { it.attr("name") != "notes" } ?: bodies.firstOrNull()
        val notesBody = bodies.firstOrNull { it.attr("name") == "notes" }

        if (mainBody != null) {
            parseBody(mainBody, builder, covers, level = 0)
        }
        if (notesBody != null) {
            val notes = notesBody.childrenNamed("section").filter { it.name == "section" }
            if (notes.isNotEmpty()) {
                builder.heading("Примечания", 1, null)
                parseBody(notesBody, builder, covers, level = 0)
            }
        }

        if (annotation != null) {
            // annotation is exposed separately; also keep it visible in the flow
        }

        val binaries = mutableMapOf<String, String>()
        for (b in bookNode.childrenNamed("binary")) {
            val id = b.attr("id") ?: continue
            val content = b.text().replace(Regex("[\\s\\r\\n]"), "")
            if (content.isNotEmpty()) binaries[id] = content
        }
        val coverBase64 = coverId?.let { binaries[it] }

        return Book(
            title = title,
            authors = authors,
            annotation = annotation,
            blocks = builder.build(),
            toc = builder.tocEntries(),
            coverBase64 = coverBase64,
        )
    }

    private fun parseAuthors(titleInfo: XmlNode?): List<String> {
        if (titleInfo == null) return emptyList()
        val out = mutableListOf<String>()
        for (author in titleInfo.childrenNamed("author")) {
            val parts = listOf("first-name", "middle-name", "last-name")
                .mapNotNull { author.firstChild(it)?.text()?.trim() }
                .filter { it.isNotEmpty() }
            val name = parts.joinToString(" ").trim()
            if (name.isNotEmpty()) {
                out.add(name)
            } else {
                val nick = author.firstChild("nickname")?.text()?.trim()
                if (!nick.isNullOrEmpty()) out.add(nick)
            }
        }
        return out
    }

    private fun nodeText(node: XmlNode): String {
        val parts = mutableListOf<String>()
        for (child in node.elements()) {
            when (child.name) {
                "p", "v", "text-author", "subtitle" -> {
                    val t = child.text().trim()
                    if (t.isNotEmpty()) parts.add(t)
                }
                "empty-line" -> Unit
                else -> {
                    val t = nodeText(child).trim()
                    if (t.isNotEmpty()) parts.add(t)
                }
            }
        }
        if (parts.isEmpty()) {
            val t = node.text().trim()
            if (t.isNotEmpty()) parts.add(t)
        }
        return parts.joinToString("\n\n")
    }

    private fun parseBody(body: XmlNode, builder: BlockBuilder, images: MutableMap<String, String>, level: Int) {
        for (child in body.elements()) {
            when (child.name) {
                "title" -> parseTitle(child, builder, level)
                "section" -> parseSection(child, builder, images, level)
                "epigraph" -> parseEpigraph(child, builder)
                "p" -> builder.paragraph(child.text())
                "subtitle" -> builder.subtitle(child.text())
                "empty-line" -> builder.empty()
                "image" -> builder.image(child.attr("href"))
                "poem" -> parsePoem(child, builder)
                "annotation" -> parseAnnotation(child, builder)
                else -> {
                    val t = child.text().trim()
                    if (t.isNotEmpty()) builder.paragraph(t)
                }
            }
        }
    }

    private fun parseSection(section: XmlNode, builder: BlockBuilder, images: MutableMap<String, String>, level: Int) {
        val titleNode = section.firstChild("title")
        val titleText = titleNode?.let { nodeText(it).replace("\n\n", " ").trim() }.orEmpty()
        if (titleNode != null && titleText.isNotEmpty()) {
            val id = section.attr("id")
            builder.heading(titleText, level + 1, id)
        }
        for (child in section.elements()) {
            when (child.name) {
                "title" -> Unit
                "section" -> parseSection(child, builder, images, level + 1)
                "epigraph" -> parseEpigraph(child, builder)
                "p" -> builder.paragraph(child.text())
                "subtitle" -> builder.subtitle(child.text())
                "empty-line" -> builder.empty()
                "image" -> builder.image(child.attr("href"))
                "poem" -> parsePoem(child, builder)
                "annotation" -> parseAnnotation(child, builder)
                else -> {
                    val t = child.text().trim()
                    if (t.isNotEmpty()) builder.paragraph(t)
                }
            }
        }
    }

    private fun parseTitle(title: XmlNode, builder: BlockBuilder, level: Int) {
        val paragraphs = title.childrenNamed("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        val whole = if (paragraphs.isEmpty()) title.text().trim() else paragraphs.joinToString(" ")
        if (whole.isEmpty()) return
        builder.heading(whole, level + 1, null)
    }

    private fun parseEpigraph(epigraph: XmlNode, builder: BlockBuilder) {
        for (p in epigraph.childrenNamed("p")) {
            val t = p.text().trim()
            if (t.isNotEmpty()) builder.epigraph(t)
        }
        for (a in epigraph.childrenNamed("text-author")) {
            val t = a.text().trim()
            if (t.isNotEmpty()) builder.epigraphAuthor(t)
        }
    }

    private fun parsePoem(poem: XmlNode, builder: BlockBuilder) {
        val title = poem.firstChild("title")?.let { nodeText(it).replace("\n\n", " ").trim() }
        if (!title.isNullOrEmpty()) builder.subtitle(title)
        for (stanza in poem.childrenNamed("stanza")) {
            for (v in stanza.childrenNamed("v")) {
                val t = v.text().trim()
                if (t.isNotEmpty()) builder.poemLine(t)
            }
        }
        for (a in poem.childrenNamed("text-author")) {
            val t = a.text().trim()
            if (t.isNotEmpty()) builder.epigraphAuthor(t)
        }
    }

    private fun parseAnnotation(node: XmlNode, builder: BlockBuilder) {
        for (p in node.childrenNamed("p")) {
            val t = p.text().trim()
            if (t.isNotEmpty()) builder.annotation(t)
        }
    }

    /** Accumulates blocks, keeping indexes and TOC in sync. */
    private class BlockBuilder {
        private val blocks = mutableListOf<Block>()
        private val toc = mutableListOf<TocEntry>()

        private fun add(kind: BlockKind, rawText: String, href: String? = null): Int {
            val text = rawText.replace('\u00A0', ' ').replace(Regex("[ \\t]+"), " ").trim()
            if (kind == BlockKind.IMAGE) {
                val index = blocks.size
                blocks.add(Block(index, kind, "", href))
                return index
            }
            if (text.isEmpty()) {
                val index = blocks.size
                blocks.add(Block(index, BlockKind.EMPTY, ""))
                return index
            }
            // avoid a run of empty blocks
            if (blocks.isNotEmpty() && blocks.last().kind == BlockKind.EMPTY &&
                blocks.size >= 2 && blocks[blocks.size - 2].kind == BlockKind.EMPTY
            ) {
                return blocks.size - 1
            }
            val index = blocks.size
            blocks.add(Block(index, kind, text, href))
            return index
        }

        fun paragraph(text: String) = add(BlockKind.PARAGRAPH, text)
        fun subtitle(text: String) = add(BlockKind.SUBTITLE, text)
        fun annotation(text: String) = add(BlockKind.ANNOTATION, text)
        fun epigraph(text: String) = add(BlockKind.EPIGRAPH, text)
        fun epigraphAuthor(text: String) = add(BlockKind.EPIGRAPH_AUTHOR, text)
        fun poemLine(text: String) = add(BlockKind.POEM, text)
        fun empty() = add(BlockKind.EMPTY, "")
        fun image(href: String?) = add(BlockKind.IMAGE, "", href)

        fun heading(text: String, level: Int, id: String?) {
            val kind = when (level) {
                1 -> BlockKind.HEADING1
                2 -> BlockKind.HEADING2
                else -> BlockKind.HEADING3
            }
            val index = add(kind, text)
            toc.add(TocEntry(text, index, level.coerceIn(1, 3)))
        }

        fun build(): List<Block> {
            // trim leading/trailing empty blocks
            var from = 0
            var to = blocks.size
            while (from < to && blocks[from].kind == BlockKind.EMPTY) from++
            while (to > from && blocks[to - 1].kind == BlockKind.EMPTY) to--
            if (from == 0 && to == blocks.size) return blocks.toList()
            return blocks.subList(from, to).mapIndexed { i, b -> b.copy(index = i) }
        }

        fun tocEntries(): List<TocEntry> {
            val all = build()
            // remap toc to trimmed indexes
            val mapping = mutableMapOf<Int, Int>()
            var shift = 0
            val total = blocks.size
            // recompute shift as number of leading empties dropped
            var from = 0
            while (from < total && blocks[from].kind == BlockKind.EMPTY) from++
            shift = from
            val result = mutableListOf<TocEntry>()
            for (e in toc) {
                val newIndex = e.blockIndex - shift
                if (newIndex >= 0 && newIndex < all.size) {
                    result.add(e.copy(blockIndex = newIndex))
                }
            }
            mapping.clear()
            return result
        }
    }
}
