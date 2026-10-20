package com.example.kolmafiaandroid.tools

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.card
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.label
import com.google.android.material.appbar.MaterialToolbar

/**
 * Full-screen dialog showing the list of available tools.
 */
class ToolsFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(DialogFragment.STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Toolbar
        val toolbar = MaterialToolbar(requireContext()).apply {
            title = "Tools"
            setNavigationOnClickListener { dismiss() }
        }
        view.addView(toolbar)

        // RecyclerView for tools list
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ToolsAdapter(requireContext(), ToolRegistry.tools) { tool ->
                dismiss()
                if (tool.page != null) (activity as? com.example.kolmafiaandroid.MainActivity)?.openGamePage(tool.page) else tool.open(parentFragmentManager!!)
            }
        }
        view.addView(recyclerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        return view
    }

    /** A heading or a tool, in display order: tools grouped under Shopping, Social, Character... */
    private sealed class Row {
        class Header(val text: String) : Row()
        class Item(val tool: Tool) : Row()
    }

    private class ToolsAdapter(
        private val context: android.content.Context,
        tools: List<Tool>,
        private val onClick: (Tool) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows: List<Row> = ToolRegistry.groupOrder.flatMap { g ->
            val inGroup = tools.filter { it.group == g }
            if (inGroup.isEmpty()) emptyList() else listOf<Row>(Row.Header(g)) + inGroup.map { Row.Item(it) }
        }

        inner class ToolHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tool_title)
            val subtitle: TextView = view.findViewById(R.id.tool_subtitle)
        }

        class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val d = context.resources.displayMetrics.density
                return HeaderHolder(TextView(context).apply {
                    textSize = 12f
                    letterSpacing = 0.1f
                    setTextColor(0xFF4CAF50.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
            return ToolHolder(LayoutInflater.from(context).inflate(R.layout.item_tool, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.text.uppercase()
                is Row.Item -> {
                    val h = holder as ToolHolder
                    h.title.text = row.tool.title
                    h.subtitle.text = row.tool.subtitle
                    h.itemView.setOnClickListener { onClick(row.tool) }
                }
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            ToolsFragment().show(fragmentManager, "tools")
        }
    }
}