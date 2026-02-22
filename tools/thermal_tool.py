import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import json
import os
from pathlib import Path

class ThermalConfigTool:
    def __init__(self, root):
        self.root = root
        self.root.title("Thermodynamica - Thermal Property Tool")
        self.root.geometry("600x500")
        
        self.setup_ui()
        
    def setup_ui(self):
        main_frame = ttk.Frame(self.root, padding="10")
        main_frame.pack(fill=tk.BOTH, expand=True)

        # Block ID
        ttk.Label(main_frame, text="Block Registry Name:").grid(row=0, column=0, sticky=tk.W, pady=5)
        self.block_id = ttk.Entry(main_frame, width=40)
        self.block_id.grid(row=0, column=1, columnspan=2, sticky=tk.W, pady=5)
        self.block_id.insert(0, "minecraft:stone")

        # Conductivity
        ttk.Label(main_frame, text="Conductivity:").grid(row=1, column=0, sticky=tk.W, pady=5)
        self.conductivity = tk.DoubleVar(value=1.0)
        ttk.Scale(main_frame, from_=0.0, to=10.0, variable=self.conductivity, orient=tk.HORIZONTAL).grid(row=1, column=1, sticky=tk.EW, pady=5)
        ttk.Entry(main_frame, textvariable=self.conductivity, width=10).grid(row=1, column=2, padx=5)

        # Transfer Rate
        ttk.Label(main_frame, text="Transfer Rate:").grid(row=2, column=0, sticky=tk.W, pady=5)
        self.transfer_rate = tk.DoubleVar(value=1.0)
        ttk.Scale(main_frame, from_=0.0, to=10.0, variable=self.transfer_rate, orient=tk.HORIZONTAL).grid(row=2, column=1, sticky=tk.EW, pady=5)
        ttk.Entry(main_frame, textvariable=self.transfer_rate, width=10).grid(row=2, column=2, padx=5)

        # Dissipation Rate
        ttk.Label(main_frame, text="Dissipation Rate:").grid(row=3, column=0, sticky=tk.W, pady=5)
        self.dissipation_rate = tk.DoubleVar(value=0.05)
        ttk.Scale(main_frame, from_=0.0, to=1.0, variable=self.dissipation_rate, orient=tk.HORIZONTAL).grid(row=3, column=1, sticky=tk.EW, pady=5)
        ttk.Entry(main_frame, textvariable=self.dissipation_rate, width=10).grid(row=3, column=2, padx=5)

        ttk.Separator(main_frame, orient=tk.HORIZONTAL).grid(row=4, column=0, columnspan=3, sticky=tk.EW, pady=20)

        # Save Options
        self.save_mode = tk.StringVar(value="existing")
        ttk.Radiobutton(main_frame, text="Update Existing JSON File", variable=self.save_mode, value="existing").grid(row=5, column=0, columnspan=2, sticky=tk.W)
        ttk.Radiobutton(main_frame, text="Create Brand New Data Pack", variable=self.save_mode, value="new").grid(row=6, column=0, columnspan=2, sticky=tk.W)

        # Action Buttons
        button_frame = ttk.Frame(main_frame)
        button_frame.grid(row=7, column=0, columnspan=3, pady=20)
        
        ttk.Button(button_frame, text="Process Action", command=self.handle_save).pack(side=tk.LEFT, padx=10)
        ttk.Button(button_frame, text="Exit", command=self.root.quit).pack(side=tk.LEFT, padx=10)

        status_frame = ttk.LabelFrame(main_frame, text="Status Output")
        status_frame.grid(row=8, column=0, columnspan=3, sticky=tk.NSEW, pady=10)
        self.status_text = tk.Text(status_frame, height=5, width=60)
        self.status_text.pack(padx=5, pady=5)

    def log(self, message):
        self.status_text.insert(tk.END, message + "\n")
        self.status_text.see(tk.END)

    def handle_save(self):
        mode = self.save_mode.get()
        block = self.block_id.get().strip()
        
        if not block:
            messagebox.showerror("Error", "Please enter a block ID")
            return

        props = {
            "conductivity": round(self.conductivity.get(), 3),
            "transfer_rate": round(self.transfer_rate.get(), 3),
            "dissipation_rate": round(self.dissipation_rate.get(), 3)
        }

        if mode == "existing":
            self.update_existing_file(block, props)
        else:
            self.create_new_datapack(block, props)

    def update_existing_file(self, block, props):
        filepath = filedialog.askopenfilename(
            title="Select Thermal Property JSON",
            filetypes=[("JSON files", "*.json")],
            initialdir="./datapacks"
        )
        if not filepath:
            return

        try:
            with open(filepath, 'r') as f:
                data = json.load(f)
            
            if "blocks" not in data:
                data["blocks"] = {}
            
            data["blocks"][block] = props
            
            with open(filepath, 'w') as f:
                json.dump(data, f, indent=2)
            
            self.log(f"Updated {block} in {os.path.basename(filepath)}")
            messagebox.showinfo("Success", f"Block {block} updated!")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to update file: {str(e)}")

    def create_new_datapack(self, block, props):
        pack_name = filedialog.asksaveasfilename(
            title="Enter New Data Pack Name",
            initialdir="./datapacks"
        )
        if not pack_name:
            return

        # Simple folder name from path
        folder_name = os.path.basename(pack_name).replace(" ", "_").lower()
        base_path = Path("datapacks") / folder_name
        
        try:
            # Create structure
            prop_dir = base_path / "data" / "thermodynamica" / "thermal_properties"
            prop_dir.mkdir(parents=True, exist_ok=True)
            
            # pack.mcmeta
            mcmeta = {
                "pack": {
                    "pack_format": 15,
                    "description": f"Custom thermal properties pack: {folder_name}"
                }
            }
            with open(base_path / "pack.mcmeta", 'w') as f:
                json.dump(mcmeta, f, indent=2)
            
            # Property file
            prop_data = {
                "blocks": {
                    block: props
                }
            }
            with open(prop_dir / f"{folder_name}.json", 'w') as f:
                json.dump(prop_data, f, indent=2)
                
            self.log(f"Created new data pack at: {base_path}")
            messagebox.showinfo("Success", f"Data pack '{folder_name}' created successfully!")
            
        except Exception as e:
            messagebox.showerror("Error", f"Failed to create data pack: {str(e)}")

if __name__ == "__main__":
    root = tk.Tk()
    app = ThermalConfigTool(root)
    root.mainloop()
