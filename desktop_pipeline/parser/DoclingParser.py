import os
import logging
from typing import Dict, Any, List

logger = logging.getLogger("DoclingParser")

class DoclingParser:
    @staticmethod
    def parse_document(file_path: str, force_ocr: bool = False) -> Dict[str, Any]:
        """
        Parses a PDF/DOCX using Docling layout-aware parser.
        Returns: { "markdown": str, "tables_html": list[str], "metadata": dict }
        """
        try:
            from docling.document_converter import DocumentConverter
            from docling.datamodel.pipeline_options import PdfPipelineOptions
            
            logger.info(f"Parsing document '{file_path}' with Docling (force_ocr={force_ocr})...")
            
            # Configure pipeline options
            pipeline_options = PdfPipelineOptions()
            pipeline_options.do_ocr = force_ocr  # Enable OCR if forced or scanned
            
            converter = DocumentConverter(
                pipeline_options=pipeline_options
            )
            
            result = converter.convert(file_path)
            
            # Export to markdown
            markdown_content = result.document.export_to_markdown()
            
            # If the output is empty and we haven't forced OCR, retry with OCR enabled
            if not markdown_content.strip() and not force_ocr and file_path.lower().endswith(".pdf"):
                logger.info(f"Empty text returned. Retrying '{file_path}' with OCR enabled...")
                return DoclingParser.parse_document(file_path, force_ocr=True)
            
            # Extract HTML tables to preserve colspan/rowspan details
            tables_html = []
            if hasattr(result.document, "tables"):
                for t in result.document.tables:
                    try:
                        tables_html.append(t.export_to_html())
                    except Exception as te:
                        logger.warning(f"Failed to export table to HTML: {te}")
                        
            metadata = {}
            if hasattr(result, "pages") and result.pages:
                metadata["page_count"] = len(result.pages)
            
            return {
                "markdown": markdown_content,
                "tables_html": tables_html,
                "metadata": metadata
            }
        except ImportError:
            logger.warning("Docling is not installed/available. Falling back to lightweight pypdf parsing.")
            try:
                import pypdf
                reader = pypdf.PdfReader(file_path)
                text_pages = []
                for idx, page in enumerate(reader.pages):
                    text_pages.append(f"## Page {idx+1}\n\n" + (page.extract_text() or ""))
                return {
                    "markdown": "\n\n".join(text_pages),
                    "tables_html": [],
                    "metadata": {"parsed_via": "pypdf_fallback", "page_count": len(reader.pages)}
                }
            except ImportError:
                logger.error("Neither docling nor pypdf is installed. Cannot parse PDF.")
                raise ImportError("Please install docling or pypdf to parse PDF files.")
        except Exception as e:
            logger.error(f"Docling parsing error for '{file_path}': {e}")
            raise e
