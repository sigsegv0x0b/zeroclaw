package ai.zeroclaw.app.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contacts search/add, mirroring OpenClaw's `contacts.search` and
 * `contacts.add`. Reads need READ_CONTACTS, writes need WRITE_CONTACTS; both
 * are runtime permissions granted from Settings.
 */
object ContactsCapabilities {

    suspend fun search(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.granted(context, android.Manifest.permission.READ_CONTACTS)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Contacts permission is not granted. Open ZeroClaw → Settings → Device " +
                        "capabilities → Contacts and grant it.",
                )
            }
            val query = args.string("query")?.trim().orEmpty()
            val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
            val resolver = context.contentResolver
            val selection = if (query.isEmpty()) {
                null
            } else {
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'"
            }
            val selectionArgs = if (query.isEmpty()) {
                null
            } else {
                arrayOf("%${escapeLike(query)}%")
            }
            val rows = buildList {
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ),
                    selection,
                    selectionArgs,
                    "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC LIMIT $limit",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIndex =
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    while (cursor.moveToNext()) {
                        add(cursor.getString(idIndex) to (cursor.getString(nameIndex) ?: "(unnamed)"))
                    }
                }
            }
            if (rows.isEmpty()) {
                return@withContext CapabilityOutcome(true, "No contacts matched \"$query\".")
            }
            val text = rows.joinToString("\n") { (id, name) ->
                val phones = readStrings(
                    resolver,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    id,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                val emails = readStrings(
                    resolver,
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    id,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                )
                buildString {
                    append("- $name")
                    if (phones.isNotEmpty()) append("  phones=${phones.joinToString(",")}")
                    if (emails.isNotEmpty()) append("  emails=${emails.joinToString(",")}")
                }
            }
            CapabilityOutcome(true, "${rows.size} contact(s):\n$text")
        }

    suspend fun add(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.granted(context, android.Manifest.permission.WRITE_CONTACTS)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Contacts permission is not granted. Open ZeroClaw → Settings → Device " +
                        "capabilities → Contacts and grant it.",
                )
            }
            val given = args.string("given_name")?.trim().orEmpty()
            val family = args.string("family_name")?.trim().orEmpty()
            val display = args.string("display_name")?.trim().orEmpty()
            if (given.isEmpty() && family.isEmpty() && display.isEmpty()) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Provide at least one of given_name, family_name or display_name.",
                )
            }
            val phones = stringList(args, "phone_numbers")
            val emails = stringList(args, "emails")
            val resolver = context.contentResolver
            val ops = ArrayList<ContentProviderOperation>()
            ops += ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
            ops += ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, given.ifBlank { null })
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, family.ifBlank { null })
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, display.ifBlank { null })
                .build()
            phones.forEach { number ->
                ops += ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                    )
                    .build()
            }
            emails.forEach { email ->
                ops += ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .withValue(
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                    )
                    .build()
            }
            val result = runCatching { resolver.applyBatch(ContactsContract.AUTHORITY, ops) }
            if (result.isFailure) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not create the contact: ${result.exceptionOrNull()?.message}",
                )
            }
            val name = display.ifBlank { listOf(given, family).filter { it.isNotBlank() }.joinToString(" ") }
            CapabilityOutcome(
                true,
                "Created contact \"$name\"" +
                    (phones.takeIf { it.isNotEmpty() }?.let { " with phones=${it.joinToString(",")}" } ?: "") +
                    (emails.takeIf { it.isNotEmpty() }?.let { " emails=${it.joinToString(",")}" } ?: "") +
                    ".",
            )
        }

    private fun readStrings(
        resolver: ContentResolver,
        uri: android.net.Uri,
        idColumn: String,
        contactId: String,
        valueColumn: String,
    ): List<String> = buildList {
        resolver.query(
            uri,
            arrayOf(valueColumn),
            "$idColumn=?",
            arrayOf(contactId),
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndexOrThrow(valueColumn)
            while (cursor.moveToNext() && size < 10) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    internal fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun stringList(args: JsonObject, key: String): List<String> {
        val array = args[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
    }
}
