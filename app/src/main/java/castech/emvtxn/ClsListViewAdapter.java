package castech.emvtxn;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class ClsListViewAdapter
{
	private List<Item> list;
	private Adapter adpter;

	public ClsListViewAdapter(Context context)
	{
		list = new ArrayList<Item>();
		adpter = new Adapter((ArrayList<Item>)list, context);
	}

	public void addItem(String title, String description)
	{
		list.add(new Item(title, description));
	}

	public void clearAllItems()
	{
		list.clear();
	}

	public ListAdapter getAdapter()
	{
		return adpter;
	}


	private class Item
	{
		private String title;
		private String description;

		public Item(String title, String description)
		{
			this.title = title;
			this.description = description;
		}
		public String getTitle()
		{
			return title;
		}
		public void setTitle(String title)
		{
			this.title = title;
		}
		public String getDescription()
		{
			return description;
		}
		public void setDescription(String description)
		{
			this.description = description;
		}
	}

	private class Adapter extends BaseAdapter
	{
		private Context context;
		private List<Item> list;

		public Adapter(ArrayList<Item> list, Context context)
		{
			this.context = context;
			this.list = list;
		}

		@Override
		public int getCount()
		{
			return list.size();
		}

		@Override
		public Object getItem(int position)
		{
			return list.get(position);
		}

		@Override
		public long getItemId(int position)
		{
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent)
		{
			LayoutInflater inflater = LayoutInflater.from(context);
			View v = inflater.inflate(R.layout.list_item, null);

			TextView tvTitle= (TextView) v.findViewById(R.id.title);
			TextView tvDescription= (TextView) v.findViewById(R.id.description);
			ImageView img= (ImageView) v.findViewById(R.id.imgDot);

			tvTitle.setText(list.get(position).getTitle());
			tvDescription.setText(list.get(position).getDescription());
			img.setImageResource(R.drawable.circle);

			return v;
		}
	}


}
