package com.google.ai.edge.gallery.data

import kotlinx.serialization.Serializable
import org.json.JSONObject

@Serializable
data class AwardedTender(
    val awardID: Int,
    val companyName: String,
    val contactPerson: String = "",
    val email: String = "",
    val telNo: String = "",
    val webAddress: String = "",
    val industry: String = "",
    val bidAmount: String = "",
    val tenderNo: String = "",
) {
    fun cacheKey(): String {
        return when {
            awardID > 0 -> awardID.toString()
            tenderNo.isNotBlank() -> tenderNo
            companyName.isNotBlank() -> companyName
            else -> "award-${companyName.hashCode()}-${contactPerson.hashCode()}"
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("awardID", awardID)
            put("companyName", companyName)
            put("contactPerson", contactPerson)
            put("email", email)
            put("telNo", telNo)
            put("webAddress", webAddress)
            put("industry", industry)
            put("bidAmount", bidAmount)
            put("tenderNo", tenderNo)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AwardedTender {
            val companyEntry = json.optJSONArray("company")?.optJSONObject(0)
            val awardEntry = json.optJSONArray("awards")?.optJSONObject(0)
            val embeddedCompanyEntry = parseEmbeddedCompanyEntry(json)

            val awardID = firstInt(
                awardEntry?.optInt("awardID"),
                companyEntry?.optInt("awardID"),
                embeddedCompanyEntry?.optInt("awardID"),
                json.optInt("awardID"),
                json.optInt("id"),
                json.optInt("tendersID"),
            )

            val companyName = firstNonBlank(
                companyEntry?.optString("company"),
                embeddedCompanyEntry?.optString("company"),
                json.optString("companyName"),
                json.optString("company"),
                json.optString("awardee"),
                json.optString("winningCompany"),
                json.optString("organization"),
                json.optString("department"),
            )

            val contactPerson = firstNonBlank(
                companyEntry?.optString("contactPerson"),
                embeddedCompanyEntry?.optString("contactPerson"),
                awardEntry?.optString("contactPerson"),
            )

            val email = firstNonBlank(
                companyEntry?.optString("email"),
                companyEntry?.optString("contactEmail"),
                embeddedCompanyEntry?.optString("email"),
                embeddedCompanyEntry?.optString("contactEmail"),
                awardEntry?.optString("email"),
                awardEntry?.optString("contactEmail"),
            )

            val telNo = firstNonBlank(
                companyEntry?.optString("contactNumber"),
                companyEntry?.optString("telephone"),
                embeddedCompanyEntry?.optString("contactNumber"),
                embeddedCompanyEntry?.optString("telephone"),
                awardEntry?.optString("contactNumber"),
                awardEntry?.optString("telephone"),
            )

            val webAddress = firstNonBlank(
                companyEntry?.optString("webAddress"),
                companyEntry?.optString("website"),
                companyEntry?.optString("websiteUrl"),
                companyEntry?.optString("url"),
                embeddedCompanyEntry?.optString("webAddress"),
                embeddedCompanyEntry?.optString("website"),
                embeddedCompanyEntry?.optString("websiteUrl"),
                embeddedCompanyEntry?.optString("url"),
                awardEntry?.optString("webAddress"),
                awardEntry?.optString("website"),
            )

            val industry = firstNonBlank(
                json.optString("category"),
                embeddedCompanyEntry?.optString("enterpriseType"),
                json.optString("type"),
                json.optString("department"),
                json.optString("province"),
                companyEntry?.optString("enterpriseType"),
            )

            val bidAmount = firstNonBlank(
                companyEntry?.optString("tenderAmount"),
                awardEntry?.optString("tenderAmount"),
                embeddedCompanyEntry?.optString("tenderAmount"),
                json.optString("tenderAmount"),
            )

            val tenderNo = firstNonBlank(
                json.optString("tender_No"),
                json.optString("tenderNo"),
                awardEntry?.optString("tender_No"),
            )

            return AwardedTender(
                awardID = awardID,
                companyName = companyName,
                contactPerson = contactPerson,
                email = email,
                telNo = telNo,
                webAddress = webAddress,
                industry = industry,
                bidAmount = bidAmount,
                tenderNo = tenderNo,
            )
        }

        private fun firstNonBlank(vararg values: String?): String {
            return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        }

        private fun firstInt(vararg values: Int?): Int {
            return values.firstOrNull { it != null && it > 0 } ?: 0
        }

        private fun parseEmbeddedCompanyEntry(json: JSONObject): JSONObject? {
            val embedded = json.optString("companyName", "")
            if (!embedded.trim().startsWith("[")) {
                return null
            }

            return try {
                JSONObject("{\"items\":$embedded}").optJSONArray("items")?.optJSONObject(0)
            } catch (_: Exception) {
                null
            }
        }
    }
}