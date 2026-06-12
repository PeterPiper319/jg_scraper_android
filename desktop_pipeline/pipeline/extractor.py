import os
import math
import re
import logging
from typing import List, Dict, Any
from schemas.tender_schema import SouthAfricanTenderExtraction
from schemas.industry_schema import IndustryClassification

logger = logging.getLogger("Extractor")

try:
    from sentence_transformers import SentenceTransformer
    import numpy as np
    HAS_SENTENCE_TRANSFORMERS = True
except ImportError:
    HAS_SENTENCE_TRANSFORMERS = False

class SemanticVectorIndex:
    def __init__(self, chunks: List[Any]):
        self.chunks = chunks
        self.embeddings = None
        
        if HAS_SENTENCE_TRANSFORMERS and chunks:
            try:
                self.model = SentenceTransformer("all-MiniLM-L6-v2")
                self.embeddings = self.model.encode([c.content for c in chunks], show_progress_bar=False)
                logger.info("SemanticVectorIndex initialized with sentence-transformers.")
            except Exception as e:
                logger.warning(f"Failed to initialize SentenceTransformer: {e}. Falling back to TF-IDF.")
                self.embeddings = None
        
        if self.embeddings is None:
            self.fallback_index = SimpleVectorIndex(chunks)

    def search(self, query: str, limit: int = 3) -> List[Any]:
        if self.embeddings is not None and len(self.chunks) > 0:
            try:
                query_embedding = self.model.encode(query, show_progress_bar=False)
                dots = np.dot(self.embeddings, query_embedding)
                norms = np.linalg.norm(self.embeddings, axis=1) * np.linalg.norm(query_embedding)
                scores = dots / (norms + 1e-8)
                
                boost_terms = ["cidb", "grading", "evaluation", "criteria", "points", "schedule", "pricing"]
                query_lower = query.lower()
                has_boost = any(t in query_lower for t in boost_terms)
                
                scored_docs = []
                for idx, chunk in enumerate(self.chunks):
                    score = float(scores[idx])
                    if chunk.content_type == "table_html" and has_boost:
                        score *= 1.5
                    scored_docs.append((chunk, score))
                    
                scored_docs.sort(key=lambda x: x[1], reverse=True)
                return [chunk for chunk, score in scored_docs[:limit]]
            except Exception as e:
                logger.error(f"Semantic search failed: {e}. Falling back to TF-IDF.")
                
        return self.fallback_index.search(query, limit)

STOP_WORDS = {
    "the", "a", "an", "and", "or", "but", "if", "then", "else", "when",
    "at", "by", "for", "from", "in", "into", "of", "off", "on", "onto",
    "out", "over", "to", "up", "with", "is", "was", "were", "be", "been",
    "has", "have", "had", "do", "does", "did", "this", "that", "these", "those"
}

def tokenize(text: str) -> List[str]:
    text_lower = text.lower()
    cleaned = re.sub(r'[^a-zA-Z0-9\s]', ' ', text_lower)
    tokens = cleaned.split()
    return [t for t in tokens if len(t) > 2 and t not in STOP_WORDS]

class SimpleVectorIndex:
    def __init__(self, chunks: List[Any]):
        self.chunks = chunks
        self.doc_count = max(len(chunks), 1)
        self.doc_terms = []
        term_doc_count = {}

        for chunk in chunks:
            tokens = tokenize(chunk.content)
            tf_map = {}
            for token in tokens:
                tf_map[token] = tf_map.get(token, 0.0) + 1.0
            for term in tf_map.keys():
                term_doc_count[term] = term_doc_count.get(term, 0) + 1
            self.doc_terms.append((chunk, tf_map))

        self.idf = {}
        for term, count in term_doc_count.items():
            self.idf[term] = math.log10(self.doc_count / float(count))

        self.processed_docs = []
        for chunk, tf_map in self.doc_terms:
            tf_idf_map = {}
            for term, tf in tf_map.items():
                tf_idf_map[term] = tf * self.idf.get(term, 0.0)
            magnitude = math.sqrt(sum(val * val for val in tf_idf_map.values()))
            self.processed_docs.append({
                "chunk": chunk,
                "tf_idf": tf_idf_map,
                "magnitude": magnitude
            })

    def search(self, query: str, limit: int = 3) -> List[Any]:
        query_tokens = tokenize(query)
        if not query_tokens or not self.processed_docs:
            return [doc["chunk"] for doc in self.processed_docs[:limit]]

        query_tf = {}
        for token in query_tokens:
            query_tf[token] = query_tf.get(token, 0.0) + 1.0

        query_tf_idf = {}
        for term, tf in query_tf.items():
            query_tf_idf[term] = tf * self.idf.get(term, 0.0)

        query_magnitude = math.sqrt(sum(val * val for val in query_tf_idf.values()))
        if query_magnitude == 0.0:
            return [doc["chunk"] for doc in self.processed_docs[:limit]]

        scored_docs = []
        for doc in self.processed_docs:
            dot_product = 0.0
            for term, query_val in query_tf_idf.items():
                doc_val = doc["tf_idf"].get(term, 0.0)
                dot_product += query_val * doc_val

            score = 0.0
            if doc["magnitude"] > 0.0:
                score = dot_product / (query_magnitude * doc["magnitude"])

            # Table boost logic for relevant queries
            if doc["chunk"].content_type == "table_html":
                boost_terms = ["cidb", "grading", "evaluation", "criteria", "points", "schedule", "pricing"]
                if any(t in query_tokens for t in boost_terms):
                    score *= 1.5

            scored_docs.append((doc["chunk"], score))

        scored_docs.sort(key=lambda x: x[1], reverse=True)
        return [chunk for chunk, score in scored_docs[:limit]]

def get_openrouter_api_key() -> str:
    key = os.environ.get("OPENROUTER_API_KEY")
    if key:
        return key

    # Look for local.properties relative to this script
    paths_to_check = [
        "../Android/src/local.properties",
        "Android/src/local.properties",
        "../../Android/src/local.properties",
        "local.properties"
    ]
    for path in paths_to_check:
        # Check from the module parent directory as well
        abs_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", path))
        if os.path.exists(abs_path):
            try:
                with open(abs_path, "r") as f:
                    for line in f:
                        if line.startswith("openrouter.api.key="):
                            k = line.split("=", 1)[1].strip()
                            if k:
                                return k
            except Exception as e:
                logger.warning(f"Failed to read key from {abs_path}: {e}")
    return ""

def run_classification(chunks: List[Any], industry_json_text: str) -> IndustryClassification:
    """
    Runs document and industry classification (Stage 1) using OpenRouter Llama 4 Scout.
    """
    import instructor
    from openai import OpenAI

    api_key = get_openrouter_api_key()
    if not api_key:
        raise ValueError("OpenRouter API key not found. Please set the OPENROUTER_API_KEY environment variable or define openrouter.api.key in Android/src/local.properties.")

    client = instructor.from_openai(
        OpenAI(
            base_url="https://openrouter.ai/api/v1",
            api_key=api_key
        ),
        mode=instructor.Mode.JSON
    )

    index = SemanticVectorIndex(chunks)
    class_chunks = index.search("tender advert type, industry category, services scope", 3)
    class_context = "\n---\n".join(
        f"File: {c.source_file}\nSection: {' > '.join(c.section_path)}\nText: {c.content}"
        for c in class_chunks
    )

    system_prompt = (
        "You are an expert procurement classifier specializing in South African government tenders.\n"
        "Classify the document type ('Tender' or 'Advert') and match it to one of the strict industry category IDs from the allowed list.\n"
        "Provide direct classification mappings conforming precisely to the schemas."
    )

    user_prompt = (
        f"ALLOWED INDUSTRIES (JSON Schema/data):\n{industry_json_text}\n\n"
        f"TEXT SNIPPETS FROM TENDER PACKAGE:\n{class_context}\n\n"
        "Select the single most applicable industry ID, name, and extract any matched specializations, skills, or capabilities from the snippets."
    )

    logger.info("Sending Stage 1 classification request to OpenRouter Llama 4 Scout...")
    response = client.chat.completions.create(
        model="meta-llama/llama-4-scout",
        response_model=IndustryClassification,
        temperature=0.0,
        presence_penalty=0.0,
        extra_body={
            "repetition_penalty": 1.0,
            "top_k": 0,
            "min_p": 0.0
        },
        max_retries=3,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]
    )
    return response

def run_extraction(chunks: List[Any]) -> SouthAfricanTenderExtraction:
    """
    Runs deep schema extraction (Stage 2) using OpenRouter Llama 4 Scout.
    """
    import instructor
    from openai import OpenAI

    api_key = get_openrouter_api_key()
    if not api_key:
        raise ValueError("OpenRouter API key not found.")

    client = instructor.from_openai(
        OpenAI(
            base_url="https://openrouter.ai/api/v1",
            api_key=api_key
        ),
        mode=instructor.Mode.JSON
    )

    index = SemanticVectorIndex(chunks)
    extract_chunks = index.search(
        "CIDB grading class of work, briefing date venue compulsory, "
        "submission box address portal, tax compliance CSD SBD forms, "
        "estimated value budget, functionality evaluation criteria threshold",
        6
    )
    extraction_context = "\n---\n".join(
        f"File: {c.source_file}\nSection: {' > '.join(c.section_path)}\nText: {c.content}"
        for c in extract_chunks
    )

    regulatory_constants = (
        "CIDB Grade Thresholds (VAT Inclusive):\n"
        "- Grade 1: Up to R500,000\n"
        "- Grade 2: Up to R1,000,000\n"
        "- Grade 3: Up to R3,000,000\n"
        "- Grade 4: Up to R6,000,000\n"
        "- Grade 5: Up to R10,000,000\n"
        "- Grade 6: Up to R20,000,000\n"
        "- Grade 7: Up to R60,000,000\n"
        "- Grade 8: Up to R200,000,000\n"
        "- Grade 9: No limit\n\n"
        "Preference Point Rules (2022 Regulations):\n"
        "- 80/20 system: Contracts R30,000 to R50,000,000\n"
        "- 90/10 system: Contracts above R50,000,000\n\n"
        "CSD MAAA Number: Starts with 'MAAA' prefix (e.g., MAAA0012345678)\n"
        "SARS TCS PIN: 10 alphanumeric characters (e.g., E9DD56E22Q)\n"
        "Tax Reference Number: 10 digits, starts with 9 (e.g., 9045478147)\n\n"
        "SEMANTIC TRANSLATION MATRIX FOR STATUTORY FORMS (MBD/SBD):\n"
        "Organs of state often do not explicitly write 'MBD 4' or 'SBD 6.1'. Instead, look for their legal/semantic intent:\n"
        "- Tax Obligation Status, SARS PIN, Invitation to Bid -> Set mbd_1_required (if municipality) or sbd_1_required (if national/provincial) to true.\n"
        "- Declaration of Interest, Conflict of Interest, Persal Number, state employment checks -> Set mbd_4_required or sbd_4_required to true.\n"
        "- Preferential Procurement Regulations 2022, 80/20 Points, 90/10 Points, preference points -> Set mbd_6_1_required or sbd_6_1_required to true.\n"
        "- Municipal utility accounts, 90 days in arrears -> Set mbd_15_required to true.\n"
        "Do NOT just look for explicit form codes. Use the semantic descriptions."
    )

    system_prompt = (
        "You are a South African tender data extraction expert.\n"
        "Extract all fields matching the provided JSON schema from the text snippets.\n"
        "Ensure all types and constraints are strictly respected.\n"
        f"Use this regulatory knowledge to evaluate and extract correct values:\n{regulatory_constants}\n\n"
        "CRITICAL EVIDENCE RULES FOR 'evidence_map':\n"
        "1. Every evidence string in 'evidence_map' must be a verbatim text excerpt/quote of a sentence, clause, or table row from the document snippets showing that value.\n"
        "2. Under no circumstances can evidence be a simple confirmation like 'Yes', 'No', 'True', 'False', 'Stipulated', 'Required', 'Not applicable', or copying of JSON keys.\n"
        "3. If the value does not exist or is not specified, return 'Not found' as the evidence."
    )

    user_prompt = (
        f"TEXT SNIPPETS:\n{extraction_context}\n\n"
        "Extract all required schema fields."
    )

    logger.info("Sending Stage 2 extraction request to OpenRouter Llama 4 Scout...")
    response = client.chat.completions.create(
        model="meta-llama/llama-4-scout",
        response_model=SouthAfricanTenderExtraction,
        temperature=0.0,
        presence_penalty=0.0,
        extra_body={
            "repetition_penalty": 1.0,
            "top_k": 0,
            "min_p": 0.0
        },
        max_retries=3,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]
    )
    return response
