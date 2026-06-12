import os
import logging
from typing import Dict, Any
from parser.DoclingParser import DoclingParser
from parser.MinerUParser import MinerUParser

logger = logging.getLogger("ParserOrchestrator")

class ParserOrchestrator:
    @staticmethod
    def parse_file(file_path: str) -> Dict[str, Any]:
        """
        Routes the file to the appropriate parser based on extension.
        Returns: { "markdown": str, "tables_html": list[str], "metadata": dict }
        """
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"File not found: {file_path}")

        ext = os.path.splitext(file_path)[1].lower()
        logger.info(f"Routing '{file_path}' to the appropriate parser (extension: {ext})")
        
        if ext == ".pdf":
            return DoclingParser.parse_document(file_path)
        elif ext in (".docx", ".pptx", ".xlsx", ".xls"):
            return MinerUParser.parse_document(file_path)
        elif ext in (".txt", ".md", ".json", ".csv"):
            try:
                with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                return {
                    "markdown": content,
                    "tables_html": [],
                    "metadata": {"parsed_via": "raw_text_reader"}
                }
            except Exception as e:
                logger.error(f"Failed to read text/markdown file '{file_path}': {e}")
                raise e
        else:
            logger.warning(f"Unsupported file format '{ext}' for file '{file_path}'. Attempting text decode.")
            try:
                with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                return {
                    "markdown": content,
                    "tables_html": [],
                    "metadata": {"parsed_via": "raw_text_reader_fallback"}
                }
            except Exception as e:
                logger.error(f"Generic text decode failed: {e}")
                raise e
