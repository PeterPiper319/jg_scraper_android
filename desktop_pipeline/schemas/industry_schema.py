from pydantic import BaseModel, Field
from typing import List, Literal

class IndustryItem(BaseModel):
    id: str = Field(description="Unique snake_case identifier for the industry")
    name: str = Field(description="Human-readable display name of the industry")
    specializations: List[str] = Field(description="Focus areas or sub-sectors within the industry")
    skills: List[str] = Field(description="Professional skills and domain expertise typical of businesses in this industry")
    capabilities: List[str] = Field(description="Physical assets, equipment, tools, registrations or certifications typical of this industry")


class IndustrySchema(BaseModel):
    industries: List[IndustryItem]


class IndustryClassification(BaseModel):
    document_type: Literal["Tender", "Advert"] = Field(description="Whether the document is a Tender (full package) or Advert (notice/advertisement)")
    industry_id: str = Field(description="The unique snake_case identifier of the matched industry from industry.json")
    classified_industry: str = Field(description="The human-readable display name of the matched industry")
    matched_specializations: List[str] = Field(default_factory=list, description="List of matched specializations from industry.json found in the text")
    matched_skills: List[str] = Field(default_factory=list, description="List of matched skills from industry.json found in the text")
    matched_capabilities: List[str] = Field(default_factory=list, description="List of matched capabilities from industry.json found in the text")
    classification_reasoning: str = Field(description="Brief explanation of why this industry and document type were selected")
