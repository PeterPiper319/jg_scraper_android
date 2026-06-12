import re
from typing import Dict, Any

# Regex patterns to find named individuals in contact fields
NAME_PREFIXES = [
    r"(?i)\battention\s*:\s*[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*",
    r"(?i)\batt\s*:\s*[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*",
    r"(?i)\bcontact\s+person\s*:\s*[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*",
    r"(?i)\benquiries\s*:\s*[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*",
    r"(?i)\bto\s*:\s*(?:Mr|Ms|Mrs|Dr|Adv)\.?\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*",
    r"\b(?:Mr|Ms|Mrs|Dr|Adv)\.?\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)+"
]

def redact_contact_names(payload: Dict[str, Any]) -> Dict[str, Any]:
    """
    Redacts personal names from contactLines text under contactDetails
    to comply with POPIA regulations.
    """
    contact_details = payload.get("contactDetails", {})
    contact_lines = contact_details.get("contactLines", [])
    
    for line in contact_lines:
        text = line.get("text", "")
        if text:
            redacted_text = text
            for pattern in NAME_PREFIXES:
                def replace_name(match):
                    full_match = match.group(0)
                    separators = [":", "Mr.", "Ms.", "Mrs.", "Dr.", "Adv.", "Mr ", "Ms ", "Mrs ", "Dr ", "Adv "]
                    for sep in separators:
                        if sep in full_match:
                            parts = full_match.split(sep, 1)
                            return f"{parts[0]}{sep}[REDACTED]"
                    return "[REDACTED]"
                
                redacted_text = re.sub(pattern, replace_name, redacted_text)
            
            line["text"] = redacted_text
            
    return payload
