import re
from typing import List, Dict, Any

class Chunk:
    def __init__(self, chunk_id: str, source_file: str, section_path: List[str], content: str, content_type: str, token_count: int = 0):
        self.chunk_id = chunk_id
        self.source_file = source_file
        self.section_path = section_path
        self.content = content
        self.content_type = content_type
        self.token_count = token_count

    def to_dict(self) -> Dict[str, Any]:
        return {
            "chunkId": self.chunk_id,
            "sourceFile": self.source_file,
            "sectionPath": self.section_path,
            "content": self.content,
            "contentType": self.content_type,
            "tokenCount": self.token_count
        }

class HierarchicalChunker:
    def __init__(self, target_chunk_size: int = 1500):
        self.target_chunk_size = target_chunk_size

    def chunk_document(self, markdown_content: str, source_file: str, html_tables: List[str] = None) -> List[Chunk]:
        """
        Chunks Markdown by header hierarchy (H1, H2, H3) and includes full section lineage.
        HTML Tables are kept as atomic, indivisible units.
        """
        if html_tables is None:
            html_tables = []

        chunks = []
        lines = markdown_content.split("\n")
        
        current_path = []
        current_section_text = []
        chunk_counter = 0

        def flush_section():
            nonlocal chunk_counter
            if not current_section_text:
                return
            
            text_block = "\n".join(current_section_text).strip()
            if not text_block:
                return

            tokens = len(text_block) // 4  # Safe token approximation
            path_str = " > ".join(current_path) if current_path else "General Details"
            full_content = f"Section: {path_str}\n\n{text_block}"
            
            chunks.append(Chunk(
                chunk_id=f"chunk_{chunk_counter:03d}",
                source_file=source_file,
                section_path=list(current_path),
                content=full_content,
                content_type="text",
                token_count=tokens
            ))
            chunk_counter += 1
            current_section_text.clear()

        header_regex = re.compile(r"^(#{1,6})\s+(.*)$")

        for line in lines:
            trimmed = line.strip()
            header_match = header_regex.match(trimmed)

            if header_match:
                # Flush the accumulated section content before changing header level
                flush_section()

                header_level = len(header_match.group(1))
                header_title = header_match.group(2).strip()

                # Adjust the path to match the current header depth
                if len(current_path) >= header_level:
                    current_path = current_path[:header_level - 1]
                while len(current_path) < header_level - 1:
                    current_path.append("Sub-section")
                current_path.append(header_title)
                
                current_section_text.append(line)
            else:
                current_section_text.append(line)
                
                # Split gracefully if chunk size exceeded
                if len("\n".join(current_section_text)) >= self.target_chunk_size:
                    flush_section()

        flush_section()

        # Add HTML tables as separate atomic blocks to prevent mid-table splitting
        for idx, table_html in enumerate(html_tables):
            tokens = len(table_html) // 4
            chunks.append(Chunk(
                chunk_id=f"table_chunk_{idx:03d}",
                source_file=source_file,
                section_path=["Tables", f"Table {idx + 1}"],
                content=table_html,
                content_type="table_html",
                token_count=tokens
            ))

        return chunks
