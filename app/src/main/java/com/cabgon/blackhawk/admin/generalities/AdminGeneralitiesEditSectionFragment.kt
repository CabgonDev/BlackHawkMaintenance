package com.cabgon.blackhawk.admin.generalities

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cabgon.blackhawk.data.user.UserSessionStore
import com.cabgon.blackhawk.databinding.FragmentAdminGeneralityEditBinding
import com.cabgon.blackhawk.util.Roles
import kotlinx.coroutines.launch

class AdminGeneralitiesEditSectionFragment : Fragment() {

    private var _b: FragmentAdminGeneralityEditBinding? = null
    private val b get() = _b!!

    private val repo = AdminGeneralitiesRepository()

    private var role: String = Roles.USER
    private val canEdit: Boolean get() = Roles.normalize(role) == Roles.DEVELOPER

    private var loadedDoc: AdminGeneralitySectionDoc? = null
    private lateinit var rowsAdapter: AdminGeneralitiesRowsAdapter

    private var colCount: Int = 3

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentAdminGeneralityEditBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        role = Roles.normalize(UserSessionStore(requireContext()).getProfile()?.role)

        b.txtRole.text = "Rol: $role"
        b.btnSave.isEnabled = canEdit
        b.btnAddRow.isEnabled = canEdit
        b.btnSave.alpha = if (canEdit) 1f else 0.35f
        b.btnAddRow.alpha = if (canEdit) 1f else 0.35f

        b.edtOrder.inputType = InputType.TYPE_CLASS_NUMBER

        rowsAdapter = AdminGeneralitiesRowsAdapter(
            initialColCount = 3,
            canEdit = canEdit,
            onDeleteRow = { idx ->
                if (!canEdit) return@AdminGeneralitiesRowsAdapter
                rowsAdapter.removeAt(idx)
            }
        )

        b.recyclerRows.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerRows.adapter = rowsAdapter

        b.btnCols2.setOnClickListener { if (canEdit) setColumns(2) }
        b.btnCols3.setOnClickListener { if (canEdit) setColumns(3) }

        b.btnAddRow.setOnClickListener {
            if (!canEdit) {
                Toast.makeText(requireContext(), "Solo Developer puede editar.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            rowsAdapter.addEmptyRow()
        }

        b.btnSave.setOnClickListener {
            if (!canEdit) {
                Toast.makeText(requireContext(), "Solo Developer puede editar.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save()
        }

        val id = requireArguments().getString(ARG_ID).orEmpty()
        if (id.isBlank()) {
            // nuevo
            applyDoc(
                AdminGeneralitySectionDoc(
                    id = "",
                    title = "",
                    order = 10,
                    tableTitle = "",
                    columns = listOf("Sistema", "Especificación", "Capacidad"),
                    rows = emptyList()
                )
            )
        } else {
            // cargar
            lifecycleScope.launch {
                val list = repo.fetchDraftOnce()
                val doc = list.firstOrNull { it.id == id }
                if (doc == null) {
                    Toast.makeText(requireContext(), "No se encontró el documento.", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                    return@launch
                }
                applyDoc(doc)
            }
        }
    }

    private fun applyDoc(doc: AdminGeneralitySectionDoc) {
        loadedDoc = doc
        b.edtTitle.setText(doc.title)
        b.edtOrder.setText(doc.order.toString())

        // tableTitle opcional: si lo dejas igual al title, la UI del viewer lo ocultará por redundancia
        b.edtTableTitle.setText(doc.tableTitle)

        colCount = doc.columns.size.coerceIn(2, 3)
        setColumns(colCount, refreshRows = false)

        if (colCount == 2) {
            b.edtCol1.setText(doc.columns.getOrNull(0).orEmpty())
            b.edtCol2.setText(doc.columns.getOrNull(1).orEmpty())
            b.edtCol3.setText("")
        } else {
            b.edtCol1.setText(doc.columns.getOrNull(0).orEmpty())
            b.edtCol2.setText(doc.columns.getOrNull(1).orEmpty())
            b.edtCol3.setText(doc.columns.getOrNull(2).orEmpty())
        }

        rowsAdapter.setRows(doc.rows, colCount)
    }

    private fun setColumns(n: Int, refreshRows: Boolean = true) {
        colCount = n
        b.btnCols2.isEnabled = canEdit
        b.btnCols3.isEnabled = canEdit

        // Mostrar/ocultar Col3
        b.col3Container.visibility = if (colCount == 3) View.VISIBLE else View.GONE

        rowsAdapter.setColumnCount(colCount, preserveData = true, refreshRows = refreshRows)
    }

    private fun save() {
        val title = b.edtTitle.text?.toString()?.trim().orEmpty()
        val order = b.edtOrder.text?.toString()?.trim()?.toIntOrNull() ?: 0

        val tableTitle = b.edtTableTitle.text?.toString()?.trim().orEmpty()

        val col1 = b.edtCol1.text?.toString()?.trim().orEmpty()
        val col2 = b.edtCol2.text?.toString()?.trim().orEmpty()
        val col3 = b.edtCol3.text?.toString()?.trim().orEmpty()

        if (title.isBlank()) {
            Toast.makeText(requireContext(), "Título requerido.", Toast.LENGTH_SHORT).show()
            return
        }
        if (col1.isBlank() || col2.isBlank() || (colCount == 3 && col3.isBlank())) {
            Toast.makeText(requireContext(), "Define los encabezados de columnas.", Toast.LENGTH_SHORT).show()
            return
        }

        val columns = if (colCount == 2) listOf(col1, col2) else listOf(col1, col2, col3)
        val rows = rowsAdapter.getRowsNormalized(colCount)

        val current = loadedDoc
        val doc = AdminGeneralitySectionDoc(
            id = current?.id.orEmpty(),
            title = title,
            order = order,
            tableTitle = tableTitle,
            columns = columns,
            rows = rows,
            isDeleted = current?.isDeleted ?: false,
            updatedAt = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            val ok = if (doc.id.isBlank()) repo.createSection(doc) else repo.updateSection(doc)
            if (ok) {
                Toast.makeText(requireContext(), "Guardado.", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                Toast.makeText(requireContext(), "No se pudo guardar.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        repo.close()
        _b = null
    }

    companion object {
        private const val ARG_ID = "id"

        fun newInstance(id: String) = AdminGeneralitiesEditSectionFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }
}
