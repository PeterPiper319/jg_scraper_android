from pydantic import BaseModel, Field, model_validator
from typing import Optional, List, Literal, Union, Any

# Define Enums using Literal types in Pydantic

InstitutionType = Literal[
    "National Department",
    "Provincial Department",
    "Metropolitan Municipality",
    "District Municipality",
    "Local Municipality",
    "State Owned Enterprise",
    "Public Entity",
    "Constitutional Institution",
    "Other"
]

ProcurementCategory = Literal[
    "Civil Works",
    "General Building Works",
    "Electrical Works",
    "Mechanical Works",
    "Specialist Works",
    "Professional Services",
    "Goods & Services",
    "ICT & Telecommunications",
    "Medical & Health",
    "Security & Guarding",
    "Catering & Hospitality",
    "Transport & Logistics",
    "Other"
]

SubmissionMethod = Literal[
    "Physical Box",
    "Electronic Portal",
    "Email",
    "Fax",
    "Dual"
]

ScoringSystem = Literal[
    "80/20",
    "90/10",
    "80/20 or 90/10",
    "None"
]

GoalType = Literal[
    "B-BBEE Contributor Level",
    "Black Ownership",
    "Black Women Ownership",
    "Youth Ownership",
    "Disability Ownership",
    "Locality",
    "SMME Promotion",
    "Military Veterans",
    "Other"
]

CidbClassOfWork = Literal[
    "GB", "CE", "ME", "EB", "EP", "EE", "SQ", "SL", "SH", "SG", "SK", "SO", "SE", "N/A"
]

ProfessionalBodyCode = Literal[
    "CIDB", "PSIRA", "NHBRC", "ECSA", "ICASA", "ISO", "SAHRA", "SABS", "SAFCEC", "MBSA", "SABPP"
]


class GeographicLocality(BaseModel):
    province: Optional[str] = None
    district_municipality: Optional[str] = None
    local_municipality: Optional[str] = None
    ward: Optional[str] = None


class TenderMetadata(BaseModel):
    tender_reference_number: str = Field(description="Unique reference number of the tender")
    tender_title: str = Field(description="Official title of the tender solicitation")
    tender_description: Optional[str] = Field(default=None, description="Detailed text summary of the tender scope")
    issuing_institution: str = Field(description="Organ of state or department issuing the tender")
    institution_type: InstitutionType = Field(description="Type of government institution")
    procurement_category: ProcurementCategory = Field(description="Primary category of the procurement package")
    geographic_locality: Optional[GeographicLocality] = None


class CompulsoryBriefing(BaseModel):
    is_compulsory: bool = Field(description="Whether attendance at the briefing session is mandatory")
    briefing_date_time: Optional[str] = Field(default=None, description="Date and time of the briefing session")
    briefing_venue: Optional[str] = Field(default=None, description="Physical location or virtual meeting details of the briefing")
    attendance_register_required: Optional[bool] = Field(default=False, description="Whether bidders must sign an attendance register")


class SiteInspection(BaseModel):
    is_compulsory: bool = Field(description="Whether a physical site inspection is mandatory")
    inspection_date_time: Optional[str] = Field(default=None, description="Date and time of the site inspection")
    inspection_venue: Optional[str] = Field(default=None, description="Venue or location of the site inspection")


class CriticalDates(BaseModel):
    publish_date: str = Field(description="Date when the tender was advertised or published (YYYY-MM-DD)")
    compulsory_briefing: CompulsoryBriefing = Field(description="Details of the briefing session")
    site_inspection: Optional[SiteInspection] = None
    closing_date_time: str = Field(description="Deadline for tender submission (ISO 8601 YYYY-MM-DDTHH:MM:SS)")
    validity_period_days: Optional[int] = Field(default=None, description="Number of days the tender submission must remain valid", ge=1)


class SubmissionMechanics(BaseModel):
    submission_method: SubmissionMethod = Field(description="Channel through which the bid proposal must be submitted")
    physical_box_address: Optional[str] = Field(default=None, description="Physical address of the tender box if method is Physical Box or Dual")
    electronic_portal_url: Optional[str] = Field(default=None, description="Website URL for electronic submissions if applicable")
    required_hard_copies: int = Field(description="Number of hard copy documents required for submission")
    required_soft_copy_medium: Optional[str] = Field(default=None, description="Format of soft copies if required (e.g., USB, CD, email)")


class InsuranceRequirements(BaseModel):
    public_liability_min_zar: Optional[float] = Field(default=None, description="Minimum ZAR value required for public liability insurance")
    professional_indemnity_min_zar: Optional[float] = Field(default=None, description="Minimum ZAR value required for professional indemnity insurance")
    car_insurance_required: Optional[bool] = Field(default=False, description="Whether motor vehicle insurance is required")


class AdministrativeCompliance(BaseModel):
    csd_registration_required: bool = Field(description="Whether Central Supplier Database registration is required")
    sars_tax_compliance_pin_required: bool = Field(description="Whether a valid SARS tax compliance status PIN is required")
    cipc_annual_returns_good_standing_required: bool = Field(description="Whether CIPC annual returns proof of good standing is required")
    coida_letter_of_good_standing_required: bool = Field(description="Whether COIDA letter of good standing is required")
    uif_registration_required: Optional[bool] = Field(default=False, description="Whether registration with UIF is required")
    paye_registration_required: Optional[bool] = Field(default=False, description="Whether registration for PAYE is required")
    bank_confirmation_letter_required: Optional[bool] = Field(default=False, description="Whether a bank confirmation letter is required")
    certified_director_id_required: Optional[bool] = Field(default=False, description="Whether certified copies of directors' IDs are required")
    certified_id_max_age_months: Optional[int] = Field(default=None, description="Maximum age of ID certifications in months (e.g. 3 or 6)")
    municipal_account_arrears_allowed_days: Optional[int] = Field(default=90, description="Maximum allowed days in arrears for municipal accounts")
    insurance_requirements: Optional[InsuranceRequirements] = None


class SbdForms(BaseModel):
    sbd_1_required: Optional[bool] = False
    sbd_3_1_required: Optional[bool] = False
    sbd_3_2_required: Optional[bool] = False
    sbd_3_3_required: Optional[bool] = False
    sbd_4_required: Optional[bool] = False
    sbd_6_1_required: Optional[bool] = False
    sbd_6_2_required: Optional[bool] = False
    sbd_7_1_required: Optional[bool] = False
    sbd_7_2_required: Optional[bool] = False
    sbd_8_required: Optional[bool] = False
    sbd_9_required: Optional[bool] = False


class MbdForms(BaseModel):
    mbd_1_required: Optional[bool] = False
    mbd_3_1_required: Optional[bool] = False
    mbd_3_2_required: Optional[bool] = False
    mbd_3_3_required: Optional[bool] = False
    mbd_4_required: Optional[bool] = False
    mbd_6_1_required: Optional[bool] = False
    mbd_6_2_required: Optional[bool] = False
    mbd_7_1_required: Optional[bool] = False
    mbd_7_2_required: Optional[bool] = False
    mbd_8_required: Optional[bool] = False
    mbd_9_required: Optional[bool] = False
    mbd_15_required: Optional[bool] = False


class StatutoryForms(BaseModel):
    sbd_forms: SbdForms = Field(description="National Government Standard Bidding Documents required")
    mbd_forms: MbdForms = Field(description="Municipal Standard Bidding Documents required")


class SpecificGoalAllocation(BaseModel):
    goal_type: GoalType = Field(description="The type of specific goal being targeted")
    allocated_points: float = Field(description="Points allocated for matching this goal")
    target_percentage_threshold: Optional[float] = Field(default=None, description="Required target ownership/participation percentage")
    required_evidence: Optional[str] = Field(default=None, description="Proof document required (e.g. BBBEE Certificate, CSD report)")


class LocalContentRequirement(BaseModel):
    is_designated_sector: bool = Field(description="Whether the tender falls under a designated local production sector")
    designated_sector_name: Optional[str] = Field(default=None, description="Name of the designated sector (e.g. Steel Products, Furniture)")
    minimum_local_content_threshold_percent: Optional[float] = Field(default=None, description="Minimum percentage threshold for local content")


class PreferentialProcurement(BaseModel):
    scoring_system_applicable: ScoringSystem = Field(description="Preference point system (e.g., 80/20 or 90/10)")
    is_income_generating: Optional[bool] = Field(default=False, description="Whether this is an income-generating contract")
    specific_goals_allocation: Optional[List[SpecificGoalAllocation]] = Field(default_factory=list)
    local_content_requirement: Optional[LocalContentRequirement] = None


class CidbRequirements(BaseModel):
    is_required: bool = Field(description="Whether CIDB registration is required for construction works")
    minimum_grade: Optional[int] = Field(default=None, description="Minimum CIDB grade required (1 to 9)", ge=1, le=9)
    potentially_emerging_status_accepted: Optional[bool] = Field(default=False, description="Whether PE (potentially emerging) contractor status is accepted")
    class_of_work: Optional[Union[CidbClassOfWork, Literal["N/A"]]] = Field(default=None, description="CIDB work class code (e.g. GB, CE)")


class ProfessionalBodyRegistration(BaseModel):
    body_code: ProfessionalBodyCode = Field(description="Regulatory body acronym (e.g. PSIRA, ECSA)")
    registration_level: Optional[str] = Field(default=None, description="Required level or category of registration")
    is_compulsory: Optional[bool] = Field(default=True, description="Whether registration is mandatory prior to award")


class IndustryCredentials(BaseModel):
    cidb_requirements: CidbRequirements = Field(description="CIDB construction industry credentials required")
    professional_body_registrations: Optional[List[ProfessionalBodyRegistration]] = Field(default_factory=list)


class FinancialCriteria(BaseModel):
    estimated_tender_value_zar: float = Field(description="Estimated value or budget of the tender in South African Rands (ZAR)")
    pricing_schedule_format_strict: Optional[bool] = Field(default=False, description="Whether the pricing template/BoQ must be strictly followed without alterations")
    vat_inclusive_pricing_required: Optional[bool] = Field(default=True, description="Whether prices must be quoted inclusive of VAT")
    audited_financials_required: bool = Field(description="Whether audited financial statements are required")
    required_financial_years: Optional[int] = Field(default=0, description="Number of years of audited financials required")
    credit_or_working_capital_proof_zar: Optional[float] = Field(default=0.0, description="Minimum ZAR amount of credit or working capital proof required")


class EvaluationCriterion(BaseModel):
    criterion_name: str = Field(description="Name of the criterion (e.g., Company Experience, Key Personnel)")
    max_points: float = Field(description="Maximum points available for this criterion")
    description: Optional[str] = Field(default=None, description="Brief description of the scoring details")


class TechnicalFunctionality(BaseModel):
    has_functionality_threshold: bool = Field(description="Whether the bid has a minimum technical functionality threshold")
    minimum_threshold_percentage: Optional[float] = Field(default=None, description="Minimum percentage score required to pass technical evaluation", ge=0, le=100)
    evaluation_criteria_matrix: Optional[List[EvaluationCriterion]] = Field(default_factory=list)


class EvidenceMap(BaseModel):
    tender_metadata_tender_reference_number: str = Field(description="Exact verbatim text snippet showing the tender reference number. Use 'Not found' if missing.")
    tender_metadata_tender_title: str = Field(description="Exact verbatim text snippet showing the tender title. Use 'Not found' if missing.")
    tender_metadata_tender_description: str = Field(description="Exact verbatim text snippet showing the detailed tender description or scope. Use 'Not found' if missing.")
    tender_metadata_issuing_institution: str = Field(description="Exact verbatim text snippet showing the issuing institution/organ of state. Use 'Not found' if missing.")
    tender_metadata_institution_type: str = Field(description="Exact verbatim text snippet showing the institution type. Use 'Not found' if missing.")
    tender_metadata_procurement_category: str = Field(description="Exact verbatim text snippet showing the procurement category. Use 'Not found' if missing.")
    
    tender_metadata_geographic_locality_province: str = Field(description="Exact verbatim text snippet showing the province. Use 'Not found' if missing.")
    tender_metadata_geographic_locality_district_municipality: str = Field(description="Exact verbatim text snippet showing the district municipality. Use 'Not found' if missing.")
    tender_metadata_geographic_locality_local_municipality: str = Field(description="Exact verbatim text snippet showing the local municipality. Use 'Not found' if missing.")
    tender_metadata_geographic_locality_ward: str = Field(description="Exact verbatim text snippet showing the ward. Use 'Not found' if missing.")
    
    critical_dates_publish_date: str = Field(description="Exact verbatim text snippet showing the publish date. Use 'Not found' if missing.")
    critical_dates_compulsory_briefing_is_compulsory: str = Field(description="Exact verbatim text snippet stating whether the briefing is compulsory. Use 'Not found' if missing.")
    critical_dates_compulsory_briefing_briefing_date_time: str = Field(description="Exact verbatim text snippet showing the briefing date and time. Use 'Not found' if missing.")
    critical_dates_compulsory_briefing_briefing_venue: str = Field(description="Exact verbatim text snippet showing the briefing venue/meeting link. Use 'Not found' if missing.")
    critical_dates_closing_date_time: str = Field(description="Exact verbatim text snippet showing the closing date/time. Use 'Not found' if missing.")
    critical_dates_validity_period_days: str = Field(description="Exact verbatim text snippet showing the validity period in days. Use 'Not found' if missing.")
    
    submission_mechanics_submission_method: str = Field(description="Exact verbatim text snippet showing the submission method. Use 'Not found' if missing.")
    submission_mechanics_physical_box_address: str = Field(description="Exact verbatim text snippet showing the physical box address. Use 'Not found' if missing.")
    submission_mechanics_electronic_portal_url: str = Field(description="Exact verbatim text snippet showing the electronic portal url. Use 'Not found' if missing.")
    submission_mechanics_required_hard_copies: str = Field(description="Exact verbatim text snippet showing the number of hard copies. Use 'Not found' if missing.")
    
    administrative_compliance_csd_registration_required: str = Field(description="Exact verbatim text snippet showing if CSD registration is required. Use 'Not found' if missing.")
    administrative_compliance_sars_tax_compliance_pin_required: str = Field(description="Exact verbatim text snippet showing if SARS tax compliance status PIN is required. Use 'Not found' if missing.")
    administrative_compliance_cipc_annual_returns_good_standing_required: str = Field(description="Exact verbatim text snippet showing if CIPC annual returns good standing is required. Use 'Not found' if missing.")
    administrative_compliance_coida_letter_of_good_standing_required: str = Field(description="Exact verbatim text snippet showing if COIDA letter of good standing is required. Use 'Not found' if missing.")
    
    preferential_procurement_scoring_system_applicable: str = Field(description="Exact verbatim text snippet showing the preference point scoring system (80/20 or 90/10). Use 'Not found' if missing.")
    
    industry_credentials_cidb_requirements_is_required: str = Field(description="Exact verbatim text snippet showing if CIDB registration is required. Use 'Not found' if missing.")
    industry_credentials_cidb_requirements_minimum_grade: str = Field(description="Exact verbatim text snippet showing the minimum CIDB grade. Use 'Not found' if missing.")
    industry_credentials_cidb_requirements_class_of_work: str = Field(description="Exact verbatim text snippet showing the CIDB class of work. Use 'Not found' if missing.")
    
    financial_criteria_estimated_tender_value_zar: str = Field(description="Exact verbatim text snippet showing the estimated tender value or budget. Use 'Not found' if missing.")
    financial_criteria_audited_financials_required: str = Field(description="Exact verbatim text snippet showing if audited financials are required. Use 'Not found' if missing.")
    
    technical_functionality_has_functionality_threshold: str = Field(description="Exact verbatim text snippet showing if a technical functionality threshold is required. Use 'Not found' if missing.")
    technical_functionality_minimum_threshold_percentage: str = Field(description="Exact verbatim text snippet showing the minimum functionality threshold percentage. Use 'Not found' if missing.")


class SouthAfricanTenderExtraction(BaseModel):
    tender_metadata: TenderMetadata
    critical_dates: CriticalDates
    submission_mechanics: SubmissionMechanics
    administrative_compliance: AdministrativeCompliance
    statutory_forms: StatutoryForms
    preferential_procurement: PreferentialProcurement
    industry_credentials: IndustryCredentials
    financial_criteria: FinancialCriteria
    technical_functionality: TechnicalFunctionality
    evidence_map: EvidenceMap

    @model_validator(mode="after")
    def validate_procurement_logic(self) -> 'SouthAfricanTenderExtraction':
        # 1. SBD vs MBD Form Exclusion
        inst_type = self.tender_metadata.institution_type
        is_municipal = inst_type in ("Metropolitan Municipality", "District Municipality", "Local Municipality")
        
        sbd = self.statutory_forms.sbd_forms
        mbd = self.statutory_forms.mbd_forms
        
        # Check if any SBD form is required
        any_sbd_true = any([
            sbd.sbd_1_required, sbd.sbd_3_1_required, sbd.sbd_3_2_required, sbd.sbd_3_3_required,
            sbd.sbd_4_required, sbd.sbd_6_1_required, sbd.sbd_6_2_required, sbd.sbd_7_1_required,
            sbd.sbd_7_2_required, sbd.sbd_8_required, sbd.sbd_9_required
        ])
        # Check if any MBD form is required
        any_mbd_true = any([
            mbd.mbd_1_required, mbd.mbd_3_1_required, mbd.mbd_3_2_required, mbd.mbd_3_3_required,
            mbd.mbd_4_required, mbd.mbd_6_1_required, mbd.mbd_6_2_required, mbd.mbd_7_1_required,
            mbd.mbd_7_2_required, mbd.mbd_8_required, mbd.mbd_9_required, mbd.mbd_15_required
        ])
        
        if is_municipal:
            if any_sbd_true:
                raise ValueError(
                    f"Institution type is a municipality ('{inst_type}'). Municipalities use MBD forms, not SBD forms. "
                    "Please set all SBD form flags to false."
                )
        else:
            if any_mbd_true:
                raise ValueError(
                    f"Institution type is a national/provincial entity or SOE ('{inst_type}'). These organs of state use SBD forms, not municipal MBD forms. "
                    "Please set all MBD form flags to false."
                )
                
        # 2. CIDB Requirements vs Procurement Category
        cidb = self.industry_credentials.cidb_requirements
        category = self.tender_metadata.procurement_category
        if cidb.is_required:
            if category not in ("Civil Works", "General Building Works", "Electrical Works", "Mechanical Works", "Specialist Works"):
                raise ValueError(
                    f"CIDB registration is only required for construction/works packages. The procurement category is '{category}', which is not a construction works category. "
                    "Please set cidb_requirements.is_required to false unless this is explicitly a construction works contract."
                )
                
        # 3. Technical Functionality Threshold Contradiction
        tech = self.technical_functionality
        has_matrix = len(tech.evaluation_criteria_matrix or []) > 0
        if has_matrix and not tech.has_functionality_threshold:
            raise ValueError(
                "An evaluation_criteria_matrix is populated, which means technical functionality criteria are being evaluated. "
                "Please set has_functionality_threshold to true."
            )
            
        if tech.has_functionality_threshold:
            val = tech.minimum_threshold_percentage
            if val is None or val <= 0.0:
                raise ValueError(
                    "has_functionality_threshold is true, but minimum_threshold_percentage is missing or <= 0%. "
                    "If a functionality threshold is enabled, it must be a valid percentage greater than 0% (e.g., 60%, 70%, 80%)."
                )
                
        # 4. Evidence Map Verbatim Quotes Validation
        evidence_dict = self.evidence_map.dict() if hasattr(self.evidence_map, "dict") else self.evidence_map.model_dump()
        confirmations = {"yes", "no", "true", "false", "n/a", "none", "not found", "stipulated", "required", "not applicable"}
        
        for field, quote in evidence_dict.items():
            quote_clean = str(quote).strip()
            
            # Reject JSON fragments/structure
            if '":' in quote_clean or quote_clean.startswith("{") or quote_clean.endswith("}"):
                raise ValueError(
                    f"Evidence for field '{field}' looks like a JSON structure/manifest snippet ('{quote}'). "
                    "Every evidence snippet must be a verbatim excerpt from the raw document text, not a JSON key-value pair."
                )
            
            # Check for lazy confirmation
            if quote_clean.lower() in confirmations:
                val_in_model = self._get_value_by_flat_name(field)
                if val_in_model is not None:
                    val_str = str(val_in_model).strip().lower()
                    if val_str not in ("not found", "not applicable", "false", "none", "", "n/a", "0", "0.0", "0.00"):
                        raise ValueError(
                            f"Evidence for field '{field}' is a lazy confirmation ('{quote}'). "
                            "Every evidence snippet must be a verbatim excerpt (sentence, clause, or table row) from the specified source document. "
                            "It cannot be a simple 'Yes', 'No', 'True', 'False', or copy of JSON keys."
                        )
                    
        return self

    def _get_value_by_flat_name(self, flat_name: str) -> Any:
        # Resolve the value from the model hierarchy based on flat_name
        if flat_name == "tender_metadata_tender_reference_number":
            return self.tender_metadata.tender_reference_number
        elif flat_name == "tender_metadata_tender_title":
            return self.tender_metadata.tender_title
        elif flat_name == "tender_metadata_tender_description":
            return self.tender_metadata.tender_description
        elif flat_name == "tender_metadata_issuing_institution":
            return self.tender_metadata.issuing_institution
        elif flat_name == "tender_metadata_institution_type":
            return self.tender_metadata.institution_type
        elif flat_name == "tender_metadata_procurement_category":
            return self.tender_metadata.procurement_category
            
        elif flat_name == "tender_metadata_geographic_locality_province":
            return self.tender_metadata.geographic_locality.province if self.tender_metadata.geographic_locality else None
        elif flat_name == "tender_metadata_geographic_locality_district_municipality":
            return self.tender_metadata.geographic_locality.district_municipality if self.tender_metadata.geographic_locality else None
        elif flat_name == "tender_metadata_geographic_locality_local_municipality":
            return self.tender_metadata.geographic_locality.local_municipality if self.tender_metadata.geographic_locality else None
        elif flat_name == "tender_metadata_geographic_locality_ward":
            return self.tender_metadata.geographic_locality.ward if self.tender_metadata.geographic_locality else None
            
        elif flat_name == "critical_dates_publish_date":
            return self.critical_dates.publish_date
        elif flat_name == "critical_dates_compulsory_briefing_is_compulsory":
            return self.critical_dates.compulsory_briefing.is_compulsory
        elif flat_name == "critical_dates_compulsory_briefing_briefing_date_time":
            return self.critical_dates.compulsory_briefing.briefing_date_time
        elif flat_name == "critical_dates_compulsory_briefing_briefing_venue":
            return self.critical_dates.compulsory_briefing.briefing_venue
        elif flat_name == "critical_dates_closing_date_time":
            return self.critical_dates.closing_date_time
        elif flat_name == "critical_dates_validity_period_days":
            return self.critical_dates.validity_period_days
            
        elif flat_name == "submission_mechanics_submission_method":
            return self.submission_mechanics.submission_method
        elif flat_name == "submission_mechanics_physical_box_address":
            return self.submission_mechanics.physical_box_address
        elif flat_name == "submission_mechanics_electronic_portal_url":
            return self.submission_mechanics.electronic_portal_url
        elif flat_name == "submission_mechanics_required_hard_copies":
            return self.submission_mechanics.required_hard_copies
            
        elif flat_name == "administrative_compliance_csd_registration_required":
            return self.administrative_compliance.csd_registration_required
        elif flat_name == "administrative_compliance_sars_tax_compliance_pin_required":
            return self.administrative_compliance.sars_tax_compliance_pin_required
        elif flat_name == "administrative_compliance_cipc_annual_returns_good_standing_required":
            return self.administrative_compliance.cipc_annual_returns_good_standing_required
        elif flat_name == "administrative_compliance_coida_letter_of_good_standing_required":
            return self.administrative_compliance.coida_letter_of_good_standing_required
            
        elif flat_name == "preferential_procurement_scoring_system_applicable":
            return self.preferential_procurement.scoring_system_applicable
            
        elif flat_name == "industry_credentials_cidb_requirements_is_required":
            return self.industry_credentials.cidb_requirements.is_required
        elif flat_name == "industry_credentials_cidb_requirements_minimum_grade":
            return self.industry_credentials.cidb_requirements.minimum_grade
        elif flat_name == "industry_credentials_cidb_requirements_class_of_work":
            return self.industry_credentials.cidb_requirements.class_of_work
            
        elif flat_name == "financial_criteria_estimated_tender_value_zar":
            return self.financial_criteria.estimated_tender_value_zar
        elif flat_name == "financial_criteria_audited_financials_required":
            return self.financial_criteria.audited_financials_required
            
        elif flat_name == "technical_functionality_has_functionality_threshold":
            return self.technical_functionality.has_functionality_threshold
        elif flat_name == "technical_functionality_minimum_threshold_percentage":
            return self.technical_functionality.minimum_threshold_percentage
            
        return None
